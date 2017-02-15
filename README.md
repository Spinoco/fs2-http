# fs2-http

Minimalistic yet powerfull http client and server with scala fs2 library. 

[![Build Status](https://travis-ci.org/Spinoco/fs2-http.svg?branch=master)](https://travis-ci.org/Spinoco/fs2-http)
[![Gitter Chat](https://badges.gitter.im/functional-streams-for-scala/fs2.svg)](https://gitter.im/fs2-http/Lobby)

## Overview

fs2-http is simple client and server library that allows you to build http clients and server using scala fs2. 
Aim of the fs2-http is to provide, simple and reusable components that allows quickly to work with various 
http protocols. 

All the code is fully asynchronous and non-blocking. Thanks to fs2 this comes with back-pressure and streaming support. 

fs2-http was build by compiling internal projects Spinoco uses for building its [product](http://www.spinoco.com/), where server side is completely implemented in fs2. 

Currently the project has only three dependencies: fs2, scodec and shapeless. As such you are free to use this with any other 
functional library, such as scalaz or cats. 


### Features

- HTTP 1.1 Client (request/reply, websocket, server-side-events) with SSL support
- HTTP 1.1 Server (request/reply, routing, websocket, server-side-events)
- HTTP Chunked encoding

### SBT

Add this to your sbt build file : 

```
libraryDependencies += "com.spinoco" %% "fs2-http" % "0.1.3" 
```

### Dependencies

version  |    scala  |   fs2  |  scodec | shapeless      
---------|-----------|--------|---------|-----------
0.1.3    | 2.11, 2.12| 0.9.2  | 1.10.3  | 2.3.2 


## Usage

Through this usage guide, following imports are required to be present for you to have the examples run in test:console: 

```
import fs2._
import fs2.util.syntax._
import spinoco.fs2.http
import http.Resources._
import spinoco.protocol.http.header._
```


### HTTP Client 

Currently fs2-http supports HTTP 1.1 protocol and allows you to connect to server with either http:// or https:// scheme. 
Simple client that requests https page body data with GET method from `https://github.com/Spinoco/fs2-http` may be constructed as:

``` 
http.client[Task]().flatMap { client =>
  val request = HttpRequest.get(Uri.https("github.com", "/Spinoco/fs2-http"))    
  client.request(request).flatMap { resp =>
    Stream.eval(resp.bodyAsString)
  }.runLog.map {
    println
  }
} 
```

Above code snippet just "builds" the http client, resulting in `fs2.Task` that will be evaluated once run (i.e. `unsafeRunAsync()`). 
Line `Stream.eval(resp.bodyAsString)`  actually evaluates consumed body of the response. Body of the 
response can be evaluated strictly (that means all output is first collected and then converted to desired type), or it can be streamed 
(that means it will be converted to the desired type as it is received from the server). Streamed body is accessible as `resp.body`. 
 
Requests to the server are modeled with [HttpRequest\[F\]](https://github.com/Spinoco/fs2-http/blob/master/src/main/scala/spinoco/fs2/http/HttpRequestOrResponse.scala#L116), and responses are modeled as [HttpResponse\[F\]](https://github.com/Spinoco/fs2-http/blob/master/src/main/scala/spinoco/fs2/http/HttpRequestOrResponse.scala#L232). Both of them share few [helpers](https://github.com/Spinoco/fs2-http/blob/master/src/main/scala/spinoco/fs2/http/HttpRequestOrResponse.scala#L17) to help you work easily with body.  

There is also simple way to sent (stream) arbitrary data to server. It is easily achieved with modifying request accordingly:

```
val stringStream: Stram[Task, String] = ???

HttpRequest.post(Uri.https("github.com", "/Spinoco/fs2-http"))
.withStreamBody(stringStream) 
```

In example above request is build as such, so when run by client it will consume `stringStream` and send with PUT request as utf8 encoded body to server. 


### WebSocket

fs2-http has support for websocket clients (RFC 6455). Websocket client is build with following construct: 

```
def wsPipe: Pipe[Task, Frame[String], Frame[String]] = { inbound =>
  val output =  time.awakeEvery[Task](1.second).map { dur => println(s"SENT $dur"); Frame.Text(s" ECHO $dur") }.take(5)
  inbound.take(5).map { in => println(("RECEIVED ", in)) }
  .mergeDrainL(output)
}

http.client[Task]().flatMap { client =>
  val request = WebSocketRequest.ws("echo.websocket.org", "/", "encoding" -> "text")  
  client.websocket(request, wsPipe).run  
}
```
 
Above code will create pipe that receives websocket frames and expects the server to echo them back. As you see, 
there is no direct access to response or body, instead websocket are always supplied with fs2 `Pipe` to send and receive data. 
This is in fact quite powerfull construct that allows you asynchronously send and receive data from / to server over http/https with 
full back-pressure support. 
 
Websocket uses `Frame\[A\]` to send and receive data. Frame is used to tag frame as binary or text. To encode/decode `A` the `scodec.Encoder` and `scodec.Decoder` is used. 

### HTTP Server

fs2-http has support to build simple yet fully functional HTTP server. Following construct builds very simple echo server: 

```
 def service(request: HttpRequestHeader, body: Stream[Task,Byte]): Stream[Task,HttpResponse[Task]] = {
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

As you see the server creates simple `Stream[F,Unit]` that when run will bind to 127.0.0.1 port 9090 and with serve results of `service` function. 
Service function is defined as `(HttpRequestHeader, Stream[F, Body])  => Stream[F, HttpResponse[F]` and allows you to perform arbitrary functionality, 
all wrapped in `fs2.Stream`. 
  
Write server service function manually may be not fun and may result to quite unreadable and hardly to manage code. As such the last component of fs2-http is server routing.    

### HTTP Server Routing 

Server routing is micro-dsl language to allow fast monadic composition of parser, that is essentially function `(HttpRequestHeader, Stream[F, Body]) => Either[HttpResponse[F], Stream[F, HttpResponse[F]]`
where on right side there is result when parser matches, and on left side there is response when parser fail to match. 

Thanks to parsers ability to compose, you can build quite complex routing constructs, that are yet readable: 

```
import spinoco.fs2.http.routing._

route[Task] ( choice(
  "example1" / "path" map { case _ => ??? }
  , "exmaple2" / decodeAs[Int] :/: decodeAs[String] map { case int :: s :: HNil => ??? }
  , "exmaple3" / body.as[Foo] :: choice(POST, PUT) map { case foo :: postOrPut :: HNil => ??? }    
  , "exmaple4" / header[`Content-Type`] map { case contentType  => ??? }    
  , "example5" / param[Int]("count") :: param[String]("query") map { case count :: query :: HNil => ??? }
  , "example6" / eval(someEffect) map { case result => ??? }
))

```

Here the choice indicates that any of the supplied routes may match, starting with very first route. Instead ??? you may supply any function producing the `Stream[Task, HttpResponse[Task]]`, 
that will be evaluated when route will match. 

Meaning of individual routes is as follows: 

- example1 : will match path "/example1/path" 
- example2 : will match path "/example2/23/some_string" and will produce 23 :: "some_string" :: HNil to map 
- example3 : will match path "/example3" and will consume body to produce `Foo` class. Map is supplied with Foo :: HttpMethod.Value :: HNil 
- example4 : will match path "/example4" and will match if header `Content-Type` is present supplying that header to map. 
- example5 : will match path "/example5?count=1&query=sql_query" supplying 1 :: "sql:query" :: HNil to map
- example6 : will match path "/example6" and then evaluating `someEffect` where the result of someEffect will be passed to map  

### Comparing to http://http4s.org/

Http4s.org is a very nice library for http, originaly started with scalaz-stream and migrating currently to fs2. The main difference between http4s.org and fs2-http is that unlike http4s.org, fs2-http has minimal amount of dependincies and is using fs2 for networking stack (tcp, ssl) as well. Unlike http4s.org you don't need special build for scalaz and cats, as fs2-http does not dependes on any of them. 




