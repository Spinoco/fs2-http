# fs2-http

Minimalistic yet powerful http client and server with scala fs2 library.

[![Build Status](https://travis-ci.org/Spinoco/fs2-http.svg?branch=master)](https://travis-ci.org/Spinoco/fs2-http)
[![Gitter Chat](https://badges.gitter.im/functional-streams-for-scala/fs2.svg)](https://gitter.im/fs2-http/Lobby)

## Overview

fs2-http is a simple client and server library that allows you to build http clients and servers using scala fs2.
The aim of fs2-http is to provide simple and reusable components that enable fast work with various
http protocols.

All the code is fully asynchronous and non-blocking. Thanks to fs2, this comes with back-pressure and streaming support.

fs2-http was built by compiling the internal projects Spinoco uses for building its [product](http://www.spinoco.com/), where the server side is completely implemented in fs2.

Currently the project only has three dependencies: fs2, scodec and shapeless. As such you are free to use this with any other
functional library, such as scalaz or cats.


### Features

- HTTP 1.1 Client (request/reply, websocket, server-side-events) with SSL support
- HTTP 1.1 Server (request/reply, routing, websocket, server-side-events)
- HTTP Chunked encoding

### SBT

Add this to your sbt build file :

for fs2 0.10.x series:
```
libraryDependencies += "com.spinoco" %% "fs2-http" % "0.3.0"
```

for fs2 0.9.x series:
```
libraryDependencies += "com.spinoco" %% "fs2-http" % "0.2.2"
```

### Dependencies

version  |    scala  |   fs2  |  scodec | shapeless      
---------|-----------|--------|---------|-----------
0.4.0-M2 | 2.11, 2.12| 1.0.0-M2 | 1.10.3  | 2.3.2
0.3.0    | 2.11, 2.12| 0.10.0 | 1.10.3  | 2.3.2
0.2.2    | 2.11, 2.12| 0.9.5  | 1.10.3  | 2.3.2


## Usage

Throughout this usage guide, the following imports are required in order for you to be able to run the examples test:console:

```
import fs2._
import fs2.util.syntax._
import cats.effect._
import cats.syntax.all._
import spinoco.fs2.http
import http._
import http.websocket._
import spinoco.protocol.http.header._
import spinoco.protocol.http._
import spinoco.protocol.http.header.value._

// import resources (Executor, Strategy, Asynchronous Channel Group, ...)
import spinoco.fs2.http.Resources._
```


### HTTP Client

Currently fs2-http supports HTTP 1.1 protocol and allows you to connect to server with either http:// or https:// scheme.
A simple client that requests https page body data with the GET method from `https://github.com/Spinoco/fs2-http` may be constructed, for example, as:

```
http.client[IO]().flatMap { client =>
  val request = HttpRequest.get[IO](Uri.https("github.com", "/Spinoco/fs2-http"))
  client.request(request).flatMap { resp =>
    Stream.eval(resp.bodyAsString)
  }.runLog.map {
    println
  }
}.unsafeRunSync()
```

The above code snippet only "builds" the http client, resulting in `IO` that will be evaluated once run (using `unsafeRunSync()`).
The line with `Stream.eval(resp.bodyAsString)` on it actually evaluates the consumed body of the response. The body of the
response can be evaluated strictly (meaning all output is first collected and then converted to the desired type), or it can be streamed (meaning it will be converted to the desired type as it is received from the server). A streamed body is accessible as `resp.body`.

Requests to the server are modeled with [HttpRequest\[F\]](https://github.com/Spinoco/fs2-http/blob/master/src/main/scala/spinoco/fs2/http/HttpRequestOrResponse.scala#L116), and responses are modeled as [HttpResponse\[F\]](https://github.com/Spinoco/fs2-http/blob/master/src/main/scala/spinoco/fs2/http/HttpRequestOrResponse.scala#L232). Both of them share several [helpers](https://github.com/Spinoco/fs2-http/blob/master/src/main/scala/spinoco/fs2/http/HttpRequestOrResponse.scala#L17) to help you work easily with the body.  

There is also a simple way to sent (stream) arbitrary data to server. It is easily achieved by modifying the request accordingly:

```
val stringStream: Stream[IO, String] = ???
implicit val encoder = StreamBodyEncoder.utf8StringEncoder[IO]

HttpRequest.get(Uri.https("github.com", "/Spinoco/fs2-http"))
.withMethod(HttpMethod.POST)
.withStreamBody(stringStream)
```

In the example above the request is build as such, to ensure that when run by the client it will consume `stringStream` and send it with PUT request as utf8 encoded body to server.


### WebSocket

fs2-http has support for websocket clients (RFC 6455). A websocket client is built with the following construct:

```
def wsPipe: Pipe[IO, Frame[String], Frame[String]] = { inbound =>
  val output =  time.awakeEvery[IO](1.second).map { dur => println(s"SENT $dur"); Frame.Text(s" ECHO $dur") }.take(5)
  inbound.take(5).map { in => println(("RECEIVED ", in)) }
  .mergeDrainL(output)
}

http.client[IO]().flatMap { client =>
  val request = WebSocketRequest.ws("echo.websocket.org", "/", "encoding" -> "text")  
  client.websocket(request, wsPipe).run  
}.unsafeRun()
```

The above code will create a pipe that receives websocket frames and expects the server to echo them back. As you see,
there is no direct access to response or body, instead websockets are always supplied with fs2 `Pipe` to send and receive data.
This is in fact quite a powerful construct that allows you to asynchronously send and receive data to/from server over http/https with full back-pressure support.

Websockets use `Frame[A]` to send and receive data. Frame is used to tag a given frame as binary or text. To encode/decode `A` the `scodec.Encoder` and `scodec.Decoder` is used.

### HTTP Server

fs2-http supports building simple yet fully functional HTTP servers. The following construct builds a very simple echo server:

```
 import java.net.InetSocketAddress
 import java.util.concurrent.Executors
 import java.nio.channels.AsynchronousChannelGroup

 val ES = Executors.newCachedThreadPool(Strategy.daemonThreadFactory("ACG"))
 implicit val ACG = AsynchronousChannelGroup.withThreadPool(ES) // http.server requires a group
 implicit val S = Strategy.fromExecutor(ES) // Async (Task) requires a strategy

 def service(request: HttpRequestHeader, body: Stream[IO,Byte]): Stream[IO,HttpResponse[IO]] = {
    if (request.path != Uri.Path / "echo") Stream.emit(HttpResponse(HttpStatusCode.Ok).withUtf8Body("Hello World"))
    else {
      val ct =  request.headers.collectFirst { case `Content-Type`(ct) => ct }.getOrElse(ContentType(MediaType.`application/octet-stream`, None, None))
      val size = request.headers.collectFirst { case `Content-Length`(sz) => sz }.getOrElse(0l)
      val ok = HttpResponse(HttpStatusCode.Ok).chunkedEncoding.withContentType(ct).withBodySize(size)

      Stream.emit(ok.copy(body = body.take(size)))
    }
  }

  http.server(new InetSocketAddress("127.0.0.1", 9090))(service).run.unsafeRun()
```

As you see the server creates a simple `Stream[F,Unit]` that, when run, will bind itself to 127.0.0.1 port 9090 and will serve the results of the `service` function.
The service function is defined as `(HttpRequestHeader, Stream[F, Body])  => Stream[F, HttpResponse[F]` and allows you to perform arbitrary functionality, all wrapped in `fs2.Stream`.

Writing a server service function manually may not be fun and may result in unreadable and hard to maintain code. As such the last component of fs2-http is server routing.    

### HTTP Server Routing

Server routing is a micro-dsl language to allow fast monadic composition of a parser, that is essentially a function `(HttpRequestHeader, Stream[F, Body]) => Either[HttpResponse[F], Stream[F, HttpResponse[F]]`
where on the right side there is the result when the parser matches, and on the left side there is the response when the parser fails to match.

Thanks to the parser's ability to compose, you can build quite complex routing constructs, that remain readable:

```
import spinoco.fs2.http.routing._
import shapeless.{HNil, ::}

route[IO] ( choice(
  "example1" / "path" map { case _ => ??? }
  , "example2" / as[Int] :/: as[String] map { case int :: s :: HNil => ??? }
  , "example3" / body.as[Foo] :: choice(Post, Put) map { case foo :: postOrPut :: HNil => ??? }
  , "example4" / header[`Content-Type`] map { case contentType  => ??? }
  , "example5" / param[Int]("count") :: param[String]("query") map { case count :: query :: HNil => ??? }
  , "example6" / eval(someEffect) map { case result => ??? }
))

```

Here the choice indicates that any of the supplied routes may match, starting with the very first route. Instead of ??? you may supply any function producing the `Stream[IO, HttpResponse[IO]]`, that will be evaluated when the route will match.

The meaning of the individual routes is as follows:

- example1 : will match path "/example1/path"
- example2 : will match path "/example2/23/some_string" and will produce 23 :: "some_string" :: HNil to map
- example3 : will match path "/example3" and will consume body to produce `Foo` class. Map is supplied with Foo :: HttpMethod.Value :: HNil
- example4 : will match path "/example4" and will match if header `Content-Type` is present supplying that header to map.
- example5 : will match path "/example5?count=1&query=sql_query" supplying 1 :: "sql:query" :: HNil to map
- example6 : will match path "/example6" and then evaluating `someEffect` where the result of someEffect will be passed to map  

### Other documentation and helpful links

- [Using custom headers](https://github.com/Spinoco/fs2-http/blob/master/doc/custom_codec.md)

### Comparing to http://http4s.org/

Http4s.org is a very usefull library for http, originaly started with scalaz-stream and currently migrating to fs2. The main difference between http4s.org and fs2-http is that unlike http4s.org, fs2-http has a minimal amount of dependencies and is using fs2 for its networking stack (tcp, ssl) as well.
