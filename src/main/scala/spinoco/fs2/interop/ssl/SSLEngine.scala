package spinoco.fs2.interop.ssl


import java.nio.ByteBuffer
import javax.net.ssl.SSLEngineResult
import javax.net.{ssl => jns}

import fs2._
import fs2.async.mutable.Semaphore
import fs2.util.Async.Ref
import fs2.util._
import fs2.util.syntax._



/**
  * Helper to establish asynchornous infterface above [[jns.SSLEngine]]
  */
trait SSLEngine[F[_]] {

  /**
    * Starts the SSL Handshake.
    * @return
    */
  def startHandshake: F[Unit]

  /**
    * Wraps presented source buffer to network Buffer.
    *
    * If the engine needs to perform any asynchronous operations, these are performed before resulting task finishes.
    *
    * User of this API shall assure that no concurrent `wrap` operations are executed
    *
    *
    * @param bytes  Unsecured application data
    * @return
    */
  def wrap(bytes: Chunk[Byte]): F[SSLEngine.Result]


  /**
    * Unwraps the data received from the network to data expected from application
    *
    * If the engine needs to perform any asynchronous operations, these are performed before resulting task finishes.
    *
    * User of this API shall assure that no concurrent `unwrap` operations are executed
    *
    * @param bytes        Secure data received over the wire
    * @return
    */
  def unwrap(bytes: Chunk[Byte]): F[SSLEngine.Result]

  /** Inbound stream (from remote party) is done, ndo no more data will be received **/
  def closeInbound: F[Unit]

  /** Outbound stream (to remote party) is done, and no more data will be sent **/
  def closeOutbound: F[Unit]

}

object SSLEngine {

  object MoreData extends Enumeration {
    val WRAP    // perform wrap, send any outstanding bytes
    , UNWRAP    // perform unwrap
    , RECEIVE_UNWRAP // receive more bytes, then unwrap
    = Value
  }

  /**
    * Result of jns.SSLEngine Wrap/UnWrap operation
    *
    * @param output       Produced data as result of the operation. This may be empty.
    * @param handshake    If there is handshake in the process, this indicates if we require wrap/unwrap to be performed
    *                     in order for handshake to be completed.
    * @param closed       If true, then the indication of closure of the connection has been received
    */
  case class Result(
    output: Chunk[Byte]
    , handshake: Option[MoreData.Value]
    , closed: Boolean
  )


  def client[F[_]](
    engine: jns.SSLEngine
    , appBufferSize: Int = 16*1024
  )(
    implicit
    F: Async[F]
    , S: Strategy
  ): F[SSLEngine[F]] = {

    def mkWrapBuffers: (ByteBuffer, ByteBuffer) =  {
       ByteBuffer.allocate(appBufferSize) ->
       ByteBuffer.allocate(engine.getSession.getPacketBufferSize)
    }

    def mkUnWrapBuffers: (ByteBuffer, ByteBuffer) =  {
      ByteBuffer.allocate(engine.getSession.getPacketBufferSize) ->
        ByteBuffer.allocate(appBufferSize)
    }


    async.semaphore(1).flatMap { wrapSemaphore =>
    F.refOf(mkWrapBuffers).flatMap  { wrapBuffers =>
    async.semaphore(1).flatMap  { unwrapSemaphore =>
    F.refOf(mkUnWrapBuffers).map { unwrapBuffers =>

      new SSLEngine[F] {
        def startHandshake: F[Unit] = F.delay {
          engine.setUseClientMode(true)
          engine.beginHandshake()
        }

        def wrap(bytes: Chunk[Byte]): F[Result] =
          impl.wrap(engine, bytes, wrapBuffers, wrapSemaphore)

        def unwrap(bytes: Chunk[Byte]): F[Result] =
          impl.unwrap(engine, bytes, unwrapBuffers, unwrapSemaphore)

        def closeInbound: F[Unit] = F.delay {
          engine.closeInbound()
        }

        def closeOutbound: F[Unit] = F.delay {
          engine.closeOutbound()
        }
      }
    }}}}


  }

  object impl {


    val EmptyBytes: Chunk[Byte] = Chunk.bytes(Array.emptyByteArray, 0, 0)

    object EngineOpName extends Enumeration {
      val WRAP, UNWRAP = Value
    }

    /**
      * Perform `wrap` operation on engine.
      *
      * Note that apart of performing the wrap, this handles following:
      *
      * - acquires wrap lock
      * - if the wrap resulted in NEED_TASK, then tha task i executed with supplied `S` strategy
      * - If the buffer UNDER/OVERFLOW is signalled new destination buffer is allocated and returned
      *
      *
      * As the last operation this releases the acquired wrap lock, to prevent two concurrent wraps to be executed.
      *
      * @param engine     SSL Engine this operates on
      * @param bytes      Bytes to wrap
      * @param buffers    Contains reference to active buffers used to perform I/O.
      *                   The first buffer is buffer with application data. Second buffer is buffer with encrypted data.
      *                   Second buffer is always empty, when this finishes, while first buffer may contain data to be
      *                   used at next invocation of wrap.
      * @param sem        A semaphore that guards this wrap attempt
      */
    def wrap[F[_]](
      engine: jns.SSLEngine
      , bytes: Chunk[Byte]
      , buffers: Ref[F, (ByteBuffer, ByteBuffer)]
      , sem: Semaphore[F]
    )(implicit F: Async[F], S: Strategy): F[Result] =
      wrapUnwrap(engine,bytes,buffers,sem)(EngineOpName.WRAP)

    /**
      * Perform `un-wrap` operation on engine.
      *
      * Note that apart of performing the un-wrap, this handles following:
      *
      * - acquires un-wrap lock
      * - if the un-wrap resulted in NEED_TASK, then tha task i executed with supplied `S` strategy
      * - If the buffer UNDERFLOW is returned then we memoize the bytes with signal received and request for more bytes to input
      * - If the buffer OVERFLOW
      *
      * As the last operation this releases the acquired lock to prevent concurrent unwraps to be executed simultaneously.
      *
      * @param engine     SSL Engine this operates on
      * @param bytes      Bytes to wrap
      * @param buffers    Contains reference to active buffers used to perform I/O.
      *                   The first buffer is buffer with encrypted data. Second buffer is buffer with decrypted data.
      *                   Second buffer is always empty, when this finishes, while first buffer may contain data to be
      *                   used at next invocation of unwrap.
      *
      * @param sem        A semaphore that guards this wrap attempt
      */
    def unwrap[F[_]](
      engine: jns.SSLEngine
      , bytes: Chunk[Byte]
      , buffers: Ref[F, (ByteBuffer, ByteBuffer)]
      , sem: Semaphore[F]
    )(implicit F: Async[F], S: Strategy): F[Result] =
      wrapUnwrap(engine,bytes,buffers,sem)(EngineOpName.UNWRAP)


    /** helper to perform wrap/unwrap **/
    def wrapUnwrap[F[_]](
    engine: jns.SSLEngine
    , bytes: Chunk[Byte]
    , buffers: Ref[F, (ByteBuffer, ByteBuffer)]
    , sem: Semaphore[F]
    )(
      op: EngineOpName.Value
    )(implicit F: Async[F], S: Strategy): F[Result] = {
      import  SSLEngineResult.Status._
      import SSLEngineResult.HandshakeStatus._

      buffers.get.flatMap { case (origInput, origOutput) =>
        println(s"XXXY INCOMING: $op: ${bytes.size}")
        println(s"XXXY BUFFERS $op : in: $origInput, $origOutput")
        val in = fillBuffer(bytes, origInput)
        def go(inBuffer: ByteBuffer, outBuffer: ByteBuffer): F[Result] = {



          def release(hs: Option[MoreData.Value]):F[Result] = {
            F.delay {
              // adjust buffers to have them ready for next wrap/unwrap
              // output buffer is always drained,
              // from input buffer we remove consumed bytes and make it ready for next write
              inBuffer.compact()
              val out = buffer2Bytes(outBuffer)
              Result(out, hs, closed = false)
            }.flatMap { result =>
              println(s"XXXG RESULT $op: (${result.output.size}) ${result.handshake} ")
              if (origInput.eq(inBuffer) && origOutput.eq(outBuffer)) { println(s"XXXY ready for next $op: IN: $inBuffer, OUT: $outBuffer" ); F.pure(result) }
              else { println(s"XXXY ready for next $op (MOD): IN: $inBuffer, OUT: $outBuffer" ); buffers.modify(_ => inBuffer -> outBuffer).as(result) }
            }
          }

          println(s"XXXY about to  $op: IN: $inBuffer, OUT: $outBuffer")
          val result = op match {
            case EngineOpName.UNWRAP => engine.unwrap(inBuffer, outBuffer)
            case EngineOpName.WRAP => engine.wrap(inBuffer, outBuffer)
          }

          println(s"XXXY just done $op: IN: $inBuffer, OUT: $outBuffer, RSLT: $result")

          result.getStatus match {
            case BUFFER_OVERFLOW =>
              // indicates we need to consume data in buffer.
              // that leads to requirement to return and indicate this as the next op
              val out = op match {
                case EngineOpName.UNWRAP => MoreData.UNWRAP
                case EngineOpName.WRAP => MoreData.WRAP
              }
              release(Some(out))

            case BUFFER_UNDERFLOW =>
              // available only at unwrap, indicates we need more data before unwrap may take a place
              release(Some(MoreData.RECEIVE_UNWRAP))

            case OK =>
              result.getHandshakeStatus match {
                case NEED_TASK =>
                  // run task and repeat command
                  F.flatMap(runTasks(engine))(_ => go(inBuffer, outBuffer))

                case NEED_WRAP =>
                  // finalize and signal need to wrap
                  release(Some(MoreData.WRAP))

                case NEED_UNWRAP =>
                  // finalize and signal need to unwrap
                  release(Some(MoreData.UNWRAP))

                case NOT_HANDSHAKING | FINISHED =>
                  release(None)
              }


            case CLOSED =>
              release(None).map { _.copy(closed = true) }
          }
        }

        go(in, origOutput)
      }

    }

    /**
      * With supplied bytes fill the supplied buffer. Note that this may create new buffer, if supplied buffer is not able
      * to hold bytes supplied.
      *
      * This expected supplied buffer to be in write-ready state, while resulting buffer is in read-ready state
      *
      */
    def fillBuffer(bytes: Chunk[Byte], buffer: ByteBuffer): ByteBuffer = {
      if (bytes.isEmpty) { buffer.flip(); buffer }
      else {
        val dest =
          if (buffer.remaining() >= bytes.size)  buffer
          else {
            val bb = ByteBuffer.allocate(buffer.capacity() + bytes.size)
            buffer.flip()
            bb.put(buffer)
            bb
          }
        val bs = bytes.toBytes
        dest.put(bs.values, bs.offset, bs.size).flip()
        dest
      }
    }

    /**
      * Creates new buffer of `desired` size. Resulting buffer is ready to be written at full capacity.
      * All data in buffer are lost
      *
      * @param buffer   Buffer to resize, potentially with some data
      * @param desired  Desired size of new buffer
      * @return
      */
    def resizeBufferW(buffer: ByteBuffer, desired: Int): ByteBuffer = {
      if (buffer.capacity() >= desired) { buffer.clear(); buffer }
      else ByteBuffer.allocate(desired)
    }



    /** runs all available tasks , retruning when tasks has been finished **/
    def runTasks[F[_]](engine: jns.SSLEngine)(implicit F: Async[F], S: Strategy):F[Unit] = {
      F.delay { Option(engine.getDelegatedTask) }.flatMap {
        case None => F.pure(())
        case Some(engineTask) =>
          F.async[Unit] { cb =>
            F.delay { S {
              try { engineTask.run(); cb(Right(())) }
              catch { case t : Throwable => cb(Left(t))}
            }}
          } *> runTasks(engine)
      }
    }

    /**
      * Supplied buffer is consumed to produce bytes.
      *
      * It is expected that buffer is in write-ready state.
      *
      * Buffer is cleared to be fresh to receive new output data
      *
      * @return
      */
    def buffer2Bytes(buffer: ByteBuffer): Chunk[Byte] = {
      buffer.flip()
      val dest = Array.ofDim[Byte](buffer.remaining())
      buffer.get(dest)
      buffer.clear()
      Chunk.bytes(dest, 0, dest.length)
    }


  }

}



