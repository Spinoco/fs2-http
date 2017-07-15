package spinoco.fs2.http.websocket


import java.nio.channels.AsynchronousChannelGroup
import java.util.concurrent.Executors
import javax.net.ssl.SSLContext

import cats.effect.Effect
import fs2._
import fs2.async.mutable.Queue
import scodec.Attempt.{Failure, Successful}
import scodec.bits.ByteVector
import scodec.{Codec, Decoder, Encoder}
import spinoco.fs2.http.HttpResponse
import fs2.interop.scodec.ByteVectorChunk
import spinoco.protocol.http.codec.{HttpRequestHeaderCodec, HttpResponseHeaderCodec}
import spinoco.protocol.http.header._
import spinoco.protocol.http._
import spinoco.protocol.http.header.value.{ContentType, HttpCharset, MediaType, ProductDescription}
import spinoco.protocol.websocket.{OpCode, WebSocketFrame}
import spinoco.protocol.websocket.codec.WebSocketFrameCodec
import spinoco.fs2.http.util.chunk2ByteVector

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.Random


object WebSocket {

  /**
    * Creates a websocket to be used on server side.
    *
    * Implementation is according to RFC-6455 (https://tools.ietf.org/html/rfc6455).
    *
    * @param pipe             A websocket pipe. `I` is received from the client and `O` is sent to client.
    *                         Decoder (for I) and Encoder (for O) must be supplied.
    * @param pingInterval     An interval for the Ping / Pong protocol.
    * @param handshakeTimeout An timeout to await for handshake to be successfull. If the handshake is not completed
    *                         within supplied period, connection is terminated.
    * @param maxFrameSize     Maximum size of single websocket frame. If the binary size of single frame is larger than
    *                         supplied value, websocket will fail.
    * @tparam F
    * @return
    */
  def server[F[_], I, O](
    pipe: Pipe[F, Frame[I], Frame[O]]
    , pingInterval: Duration = 30.seconds
    , handshakeTimeout: FiniteDuration = 10.seconds
    , maxFrameSize: Int = 1024*1024
  )(header: HttpRequestHeader, input:Stream[F,Byte])(
    implicit
    R: Decoder[I]
    , W: Encoder[O]
    , F: Effect[F]
    , EC: ExecutionContext
    , S: Scheduler
  ): Stream[F,HttpResponse[F]] = {
    Stream.emit(
      impl.verifyHeaderRequest[F](header).right.map { key =>
        val respHeader = impl.computeHandshakeResponse(header, key)
        HttpResponse(respHeader, input through impl.webSocketOf(pipe, pingInterval, maxFrameSize, client2Server = false))
      }.merge
    )
  }


  /**
    * Establishes websocket connection to the server.
    *
    * Implementation is according to RFC-6455 (https://tools.ietf.org/html/rfc6455).
    *
    * If this is established successfully, then this consults `pipe` to receive/sent any frames
    * From/To server. Once the connection finishes, this will emit once None.
    *
    * If the connection was not established correctly (i.e. Authorization failure) this will not
    * consult supplied pipe and instead this will immediately emit response received from the server.
    *
    * @param request              WebSocket request
    * @param pipe                 Pipe that is consulted when websocket is established correctly
    * @param maxHeaderSize        Max size of  Http Response header received
    * @param receiveBufferSize    Size of receive buffer to use
    * @param maxFrameSize         Maximum size of single websocket frame. If the binary size of single frame is larger than
    *                             supplied value, websocket will fail.
    * @param requestCodec         Codec to encode HttpRequests Header
    * @param responseCodec        Codec to decode HttpResponse Header
    *
    */
  def client[F[_], I, O](
    request: WebSocketRequest
    , pipe: Pipe[F, Frame[I], Frame[O]]
    , maxHeaderSize: Int = 4096
    , receiveBufferSize: Int = 256 * 1024
    , maxFrameSize: Int = 1024*1024
    , requestCodec: Codec[HttpRequestHeader] = HttpRequestHeaderCodec.defaultCodec
    , responseCodec: Codec[HttpResponseHeader] = HttpResponseHeaderCodec.defaultCodec
    , sslES: => ExecutionContext = ExecutionContext.fromExecutorService(Executors.newCachedThreadPool(spinoco.fs2.http.util.mkThreadFactory("fs2-http-ssl", daemon = true)))
    , sslContext: => SSLContext = { val ctx = SSLContext.getInstance("TLS"); ctx.init(null,null,null); ctx }
  )(
    implicit
    R: Decoder[I]
    , W: Encoder[O]
    , AG: AsynchronousChannelGroup
    , EC: ExecutionContext
    , F: Effect[F]
    , S: Scheduler
  ): Stream[F, Option[HttpResponseHeader]] = {
    import spinoco.fs2.http.internal._
    import Stream._
    eval(addressForRequest(if (request.secure) HttpScheme.WSS else HttpScheme.WS, request.hostPort)).flatMap { address =>
    io.tcp.client(address, receiveBufferSize = receiveBufferSize)
    .evalMap { socket => if (request.secure) liftToSecure(sslES, sslContext)(socket) else F.pure(socket) }
    .flatMap { socket =>
      val (header, fingerprint) = impl.createRequestHeaders(request.header)
      requestCodec.encode(header) match {
        case Failure(err) => Stream.fail(new Throwable(s"Failed to encode websocket request: $err"))
        case Successful(headerBits) =>
          eval(socket.write(ByteVectorChunk(headerBits.bytes ++ `\r\n\r\n`))).flatMap { _ =>
            socket.reads(receiveBufferSize) through httpHeaderAndBody(maxHeaderSize) flatMap { case (respHeaderBytes, body) =>
              responseCodec.decodeValue(respHeaderBytes.bits) match {
                case Failure(err) => fail(new Throwable(s"Failed to decode websocket response: $err"))
                case Successful(responseHeader) =>
                  impl.validateResponse[F](header, responseHeader, fingerprint).flatMap {
                    case Some(resp) => emit(Some(resp))
                    case None => (body through impl.webSocketOf(pipe, Duration.Undefined, maxFrameSize, client2Server = true) through socket.writes(None)).drain ++ emit(None)
                  }
              }
            }
          }
      }

    }}

  }


  object impl {

    private sealed trait PingPong

    private object PingPong {
      object Ping extends PingPong
      object Pong extends PingPong
    }


    /**
      * Verifies validity of WebSocket header request (on server) and extracts WebSocket key
      */
    def verifyHeaderRequest[F[_]](header: HttpRequestHeader): Either[HttpResponse[F], ByteVector] = {
      def badRequest(s:String) = HttpResponse[F](
        header = HttpResponseHeader(
          status = HttpStatusCode.BadRequest
          , reason = HttpStatusCode.BadRequest.label
          , headers = List(
             `Content-Type`(ContentType(MediaType.`text/plain`, Some(HttpCharset.`UTF-8`), None))
          )
        )
        , body = Stream.chunk(ByteVectorChunk(ByteVector.view(s.getBytes)))
      )

      def version: Either[HttpResponse[F], Int] = header.headers.collectFirst {
        case `Sec-WebSocket-Version`(13) => Right(13)
        case `Sec-WebSocket-Version`(other) => Left(badRequest(s"Unsupported websocket version: $other"))
      }.getOrElse(Left(badRequest("Missing Sec-WebSocket-Version header")))

      def host: Either[HttpResponse[F], Unit] = header.headers.collectFirst {
        case Host(_) => Right(())
      }.getOrElse(Left(badRequest("Missing header `Host: hostname`")))

      def upgrade: Either[HttpResponse[F], Unit] = header.headers.collectFirst {
        case Upgrade(pds) if pds.exists { pd => pd.name.equalsIgnoreCase("websocket") && pd.comment.isEmpty } => Right(())
      }.getOrElse(Left(badRequest("Missing header `Upgrade: websocket`")))

      def connection: Either[HttpResponse[F], Unit] = header.headers.collectFirst {
        case Connection(s) if s.exists(_.equalsIgnoreCase("Upgrade")) => Right(())
      }.getOrElse(Left(badRequest("Missing header `Connection: upgrade`")))


      def webSocketKey: Either[HttpResponse[F], ByteVector] = header.headers.collectFirst {
        case `Sec-WebSocket-Key`(key) => Right(key)
      }.getOrElse(Left(badRequest("Missing Sec-WebSocket-Key header")))

      for {
        _ <- version.right
        _ <- host.right
        _ <- upgrade.right
        _ <- connection.right
        key <- webSocketKey.right
      } yield key

    }

    /** creates the handshake response to complete websocket handshake on server side **/
    def computeHandshakeResponse(header: HttpRequestHeader, key: ByteVector): HttpResponseHeader = {
      val fingerprint = computeFingerPrint(key)
      val headers = header.headers.collect {
        case h: `Sec-WebSocket-Protocol` => h
      }
      HttpResponseHeader(
        status = HttpStatusCode.SwitchingProtocols
        , reason = HttpStatusCode.SwitchingProtocols.label
        , headers = List(
          Upgrade(List(ProductDescription("websocket", None)))
          , Connection(List("Upgrade"))
          , `Sec-WebSocket-Accept`(fingerprint)
        ) ++ headers
      )
    }

    /**
      * Creates websocket  of supplied pipe
      *
      * @param pingInterval If Finite, defines duration when keep-alive pings are sent to client
      *                     If client won't respond with pong to 3x this internal, the websocket will be terminated
      *                     by server.
      * @param client2Server When true, this represent client -> server direction, when false this represents reverse direction
      */
    def webSocketOf[F[_], I, O](
     pipe: Pipe[F, Frame[I], Frame[O]]
     , pingInterval: Duration
     , maxFrameSize: Int
     , client2Server: Boolean
    )(
      implicit
      R: Decoder[I]
      , W: Encoder[O]
      , F: Effect[F]
      , EC: ExecutionContext
      , S: Scheduler
    ):Pipe[F, Byte, Byte] = { source: Stream[F, Byte] => Stream.suspend {
      Stream.eval(async.unboundedQueue[F, PingPong]).flatMap { pingPongQ =>
        val metronome: Stream[F, Unit] = pingInterval match {
          case fin: FiniteDuration => time.awakeEvery[F](fin).map { _ => () }
          case inf => Stream.empty
        }
        val control = controlStream[F](pingPongQ.dequeue, metronome, maxUnanswered = 3, flag = client2Server)

        source
        .through(decodeWebSocketFrame[F](maxFrameSize, client2Server))
        .through(webSocketFrame2Frame[F, I](pingPongQ))
        .through(pipe)
        .through(frame2WebSocketFrame[F, O](if (client2Server) Some(Random.nextInt()) else None))
        .mergeHaltBoth(control)
        .through(encodeWebSocketFrame[F](client2Server))

      }
    }}

    /**
      * Cuts necessary data for decoding the frame, done by partially decoding
      * the frame
      * Empty if the data couldn't be decoded yet
      * 
      * @param in Current buffer that may contain full frame
      */
    def cutFrame(in:ByteVector): Option[ByteVector] = {
      val bits = in.bits
      if (bits.size < 16) None // smallest frame is 16 bits
      else {
        val maskSize = if (bits(8)) 4 else 0
        val sz = bits.drop(9).take(7).toInt(signed = false)
        val maybeEnough =
          if (sz < 126) {
            // no extended payload size, sz bytes expected
            Some(sz.toLong + 2)
          } else if (sz == 126) {
            // next 16 bits is payload size
            if (bits.size < 32) None
            else Some(bits.drop(16).take(16).toInt(signed = false).toLong + 4)
          } else {
            // next 64 bits is payload size
            if (bits.size < 80) None
            else Some(bits.drop(16).take(64).toLong(signed = false) + 10)
          }
        maybeEnough.flatMap { sz =>
          val fullSize = sz + maskSize
          if (in.size < fullSize) None
          else Some(in.take(fullSize))
        }
      }
    }

    /**
      * Decodes websocket frame.
      *
      * This will fail when the frame failed to be decoded or when frame is larger than
      * supplied `maxFrameSize` parameter.
      *
      * @param maxFrameSize  Maximum size of the frame, including its header.
      */
    def decodeWebSocketFrame[F[_]](maxFrameSize: Int , flag: Boolean): Pipe[F, Byte, WebSocketFrame] = {
      // Returns list of raw frames and tail of
      // the buffer. Tail of the buffer cant be empty
      // (or non-empty if last one frame isn't finalized).
      def cutFrames(data: ByteVector, acc: Vector[ByteVector] = Vector.empty): (Vector[ByteVector], ByteVector) = {
        cutFrame(data) match {
          case Some(frameData) => cutFrames(data.drop(frameData.size), acc :+ frameData)
          case None => (acc, data)
        }
      }
      def go(buff: ByteVector): Stream[F, Byte] => Pull[F, WebSocketFrame, Unit] = { h0 =>
        if (buff.size > maxFrameSize) Pull.fail(new Throwable(s"Size of websocket frame exceeded max size: $maxFrameSize, current: ${buff.size}, $buff"))
        else {
          h0.pull.unconsChunk flatMap {
            case None => Pull.done  // todo: is ok to silently ignore buffer remainder ?

            case Some((chunk, tl)) =>
              val data = buff ++ chunk2ByteVector(chunk)
              cutFrames(data) match {
                case (rawFrames, _) if rawFrames.isEmpty => go(data)(tl)
                case (rawFrames, dataTail) =>
                  val pulls = rawFrames.map { data =>
                    WebSocketFrameCodec.codec.decodeValue(data.bits) match {
                      case Failure(err) => Pull.fail(new Throwable(s"Failed to decode websocket frame: $err, $data"))
                      case Successful(wsFrame) => Pull.output1(wsFrame)
                    }
                  }
                  // pulls nonempty
                  pulls.reduce(_ >> _) >> go(dataTail)(tl)
              }
          }
        }
      }
      src => go(ByteVector.empty)(src).stream
    }

    /**
      * Collects incoming frames. to produce and deserialize Frame[A].
      *
      * Also interprets WebSocket operations.
      *   - if Ping is received, supplied Queue is enqueued with true
      *   - if Pong is received, supplied Queue is enqueued with false
      *   - if Close is received, the WebSocket is terminated
      *   - if Continuation is received, the buffer of the frame is enqueued and later used to deserialize to `A`.
      *
      * @param pongQ    Queue to notify about ping/pong frames.
      */
    def webSocketFrame2Frame[F[_], A](pongQ: Queue[F, PingPong])(implicit R: Decoder[A]): Pipe[F, WebSocketFrame, Frame[A]] = {
      def decode(from: Vector[WebSocketFrame]):Pull[F, Frame[A], A] = {
        val bs = from.map(_.payload).reduce(_ ++ _)
        R.decodeValue(bs.bits) match {
          case Failure(err) => Pull.fail(new Throwable(s"Failed to decode value: $err, content: $bs"))
          case Successful(a) => Pull.pure(a)
        }
      }

      def go(buff:Vector[WebSocketFrame]): Stream[F, WebSocketFrame] => Pull[F, Frame[A], Unit] = {
        _.pull.uncons1 flatMap {
          case None => Pull.done  // todo: is ok to ignore remainder in buffer ?
          case Some((frame, tl)) =>
            frame.opcode match {
              case OpCode.Continuation => go(buff :+ frame)(tl)
              case OpCode.Text => decode(buff :+ frame).flatMap { decoded => Pull.output1(Frame.Text(decoded)) >> go(Vector.empty)(tl) }
              case OpCode.Binary =>  decode(buff :+ frame).flatMap { decoded => Pull.output1(Frame.Binary(decoded)) >> go(Vector.empty)(tl) }
              case OpCode.Ping => Pull.eval(pongQ.enqueue1(PingPong.Ping)) >> go(buff)(tl)
              case OpCode.Pong => Pull.eval(pongQ.enqueue1(PingPong.Pong)) >> go(buff)(tl)
              case OpCode.Close => Pull.done
            }
        }
      }

      src => go(Vector.empty)(src).stream
    }

    /**
      * Encodes received frome to WebSocketFrame.
      * @param maskKey  A funtion that allows to generate random masking key. Masking is applied at client -> server direction only.
      */
    def frame2WebSocketFrame[F[_], A](maskKey: => Option[Int])(implicit W: Encoder[A]): Pipe[F, Frame[A], WebSocketFrame] = {
      _.flatMap { frame =>
        W.encode(frame.a) match {
          case Failure(err) => Stream.fail(new Throwable(s"Failed to encode frame: $err (frame: $frame)"))
          case Successful(payload) =>
            val opCode = if (frame.isText) OpCode.Text else OpCode.Binary
            Stream.emit(WebSocketFrame(fin = true, (false, false, false), opCode, payload.bytes, maskKey))
        }
      }
    }


    private val pingFrame = WebSocketFrame(fin = true, (false, false, false), OpCode.Ping, ByteVector.empty, None)
    private val pongFrame = WebSocketFrame(fin = true, (false, false, false), OpCode.Pong, ByteVector.empty, None)
    private val closeFrame = WebSocketFrame(fin = true, (false, false, false), OpCode.Close, ByteVector.empty, None)

    /**
      * Encodes incoming frames to wire format.
      * @tparam F
      * @return
      */
    def encodeWebSocketFrame[F[_]](flag: Boolean): Pipe[F, WebSocketFrame, Byte] = {
      _.append(Stream.emit(closeFrame)).flatMap { wsf =>
        WebSocketFrameCodec.codec.encode(wsf) match {
          case Failure(err) => Stream.fail(new Throwable(s"Failed to encode websocket frame: $err (frame: $wsf)"))
          case Successful(data) => Stream.chunk(ByteVectorChunk(data.bytes))
        }
      }
    }

    /**
      * Creates control stream. When control stream terminates WebSocket will terminate too.
      *
      * This takes ping-pong stream, for each Ping, this responds with Pong.
      * For each Pong received this zeroes number of pings sent.
      *
      * @param pingPongs          Stream of ping pongs received
      * @param metronome          A metronome that emits time to send Ping
      * @param maxUnanswered      Max unanswered pings to await before the stream terminates.
      * @tparam F
      * @return
      */
    def controlStream[F[_]](
       pingPongs: Stream[F, PingPong]
       , metronome: Stream[F, Unit]
       , maxUnanswered: Int
       , flag: Boolean
    )(implicit F: Effect[F], EC: ExecutionContext): Stream[F, WebSocketFrame] = {
      (pingPongs either metronome)
      .mapAccumulate(0) { case (pingsSent, in) => in match {
        case Left(PingPong.Pong) => (0, Stream.empty)
        case Left(PingPong.Ping) => (pingsSent, Stream.emit(pongFrame))
        case Right(_) => (pingsSent + 1, Stream.emit(pingFrame))
      }}
      .flatMap { case (unconfirmed, out) =>
        if (unconfirmed < 3) out
        else Stream.fail(new Throwable(s"Maximum number of unconfirmed pings exceeded: $unconfirmed"))
      }
    }


    val magic = ByteVector.view("258EAFA5-E914-47DA-95CA-C5AB0DC85B11".getBytes)
    def computeFingerPrint(key: ByteVector): ByteVector =
      (ByteVector.view(key.toBase64.getBytes) ++ magic).digest("SHA-1")


    /**
      * Augments header to be correct for Websocket request (adding Sec-WebSocket-Key header) and
      * returnng the correct header with expected SHA-1 response from the server
      * @param header
      * @param random   Random generator of 16 byte websocket keys
      * @return
      */
    def createRequestHeaders(header:HttpRequestHeader, random: => ByteVector = randomBytes(16)): (HttpRequestHeader, ByteVector) = {
      val key = random
      val headers =
        header.headers.filterNot ( h =>
          h.isInstanceOf[`Sec-WebSocket-Key`]
          || h.isInstanceOf[`Sec-WebSocket-Version`]
          || h.isInstanceOf[Upgrade]
        ) ++
        List(
          `Sec-WebSocket-Key`(key)
          , `Sec-WebSocket-Version`(13)
          , Connection(List("upgrade"))
          , Upgrade(List(ProductDescription("websocket", None)))
        )

      header.copy(
        method = HttpMethod.GET
        , headers = headers
      ) -> computeFingerPrint(key)
    }

    /** random generator, ascii compatible **/
    def randomBytes(size: Int):ByteVector = {
      ByteVector.view(Random.alphanumeric.take(size).mkString.getBytes)
    }

    /**
      * Validates response received. If other than 101 status code is received, this evaluates to Some()
      * If fingerprint won't match or the websocket headers wont match the request, this fails.
      * @param request      Sent request header
      * @param response     received header
      * @param expectFingerPrint  expected fingerprint in header
      * @return
      */
    def validateResponse[F[_]](
      request: HttpRequestHeader
      , response: HttpResponseHeader
      , expectFingerPrint: ByteVector
    ): Stream[F, Option[HttpResponseHeader]] = {
      import Stream._

      def validateFingerPrint: Stream[F,Unit] =
      response.headers.collectFirst {
        case `Sec-WebSocket-Accept`(receivedFp) =>
          if (receivedFp != expectFingerPrint) fail(new Throwable(s"Websocket fingerprints won't match, expected $expectFingerPrint, but got $receivedFp"))
          else emit(())
      }.getOrElse(fail(new Throwable(s"Websocket response is missing the `Sec-WebSocket-Accept` header : $response")))

      def validateUpgrade: Stream[F,Unit] =
        response.headers.collectFirst {
          case Upgrade(pds) if pds.exists { pd => pd.name.equalsIgnoreCase("websocket")  && pd.comment.isEmpty }  => emit(())
        }.getOrElse(fail(new Throwable(s"WebSocket response must contain header 'Upgrade: websocket' : $response")))

      def validateConnection: Stream[F,Unit] =
        response.headers.collectFirst {
          case Connection(ids) if ids.exists(_.equalsIgnoreCase("upgrade")) => emit(())
        }.getOrElse(fail(new Throwable(s"WebSocket response must contain header 'Connection: Upgrade' : $response")))

      def validateProtocols: Stream[F,Unit] = {
        val received =
          response.headers.collectFirst {
            case `Sec-WebSocket-Protocol`(protocols) => protocols
          }.getOrElse(Nil)

        val expected =
          request.headers.collectFirst {
            case `Sec-WebSocket-Protocol`(protocols) => protocols
          }.getOrElse(Nil)

        if (expected.diff(received).nonEmpty) fail(new Throwable(s"Websocket protocols do not match. Expected $expected, received: $received"))
        else emit(())
      }

      def validateExtensions: Stream[F,Unit] = {
        val received =
          response.headers.collectFirst {
            case `Sec-WebSocket-Extensions`(extensions) => extensions
          }.getOrElse(Nil)

        val expected =
          request.headers.collectFirst {
            case `Sec-WebSocket-Extensions`(extensions) => extensions
          }.getOrElse(Nil)

        if (expected.diff(received).nonEmpty)  fail(new Throwable(s"Websocket extensions do not match. Expected $expected, received: $received"))
        else emit(())
      }

      if (response.status != HttpStatusCode.SwitchingProtocols) emit(Some(response))
      else {
        for {
          _ <- validateUpgrade
          _ <- validateConnection
          _ <- validateFingerPrint
          _ <- validateProtocols
          _ <- validateExtensions
        } yield None: Option[HttpResponseHeader]
      }
    }

  }

}
