# Using custom codec for http headers and requests. 

Ocassionally it is required to extends headers supported by fs2-http by some custom headers of user choice. Behind the scenes fs2-http is using scodec library for encoding and decoding codecs. So generally addin any codec is quite straigthforward. 

## Using custom Generic Header

If you are ok with receiveving your header as simple String value pair, there is simple technique using the `GenericHeader`. This allows you to encode and decode any Http Header with simple string key and value pair, where key is name of the header and value is anything after : in http header. For example : 

```
Authorization: Token someAuthorizationToken

```
may be decoded as 

```scala

GenericHeader("Authorization", "Token someAuthorizationToken") 

```

However to do so we need to supply this codec to the http client and http server. In both cases this is pretty straightforward to do: 

```scala
import scodec.Codec
import scodec.codecs.utf8

import spinoco.fs2.http

import spinoco.protocol.http.codec.HttpHeaderCodec
import spinoco.protocol.http.codec.HttpRequestHeaderCodec
import spinoco.protocol.http.header.GenericHeader
import spinoco.protocol.http.header.HttpHeader

val genericHeaderAuthCodec: HttpCodec[HttpHeader] = 
 utf8.xmap[GenericHeader](s => GenericHeader("Authorization", s), _.value).upcast[HttpHeader]
 
val headerCodec: Codec[HttpHeader]= 
 HttpHeaderCodec.codec(Int.MaxValue, ("Authorization" -> genericHeaderAuthCodec))
 

http.client(
   requestCodec = HttpRequestHeaderCodec.codec(headerCodec)
   , responseCodec = HttpResponseHeaderCodec.codec(headerCodec)
) map { client => 
 /** your code with client **/
}

http.server(
 bindTo = ??? // your ip where you want bind server to 
   , requestCodec = HttpRequestHeaderCodec.codec(headerCodec)
   , responseCodec = HttpResponseHeaderCodec.codec(headerCodec)
) flatMap { server => 
  /** your code with server **/
}

```

Note that this technique, effectivelly causes to turn-off any already supported Authorization header codecs, which you man not want to. Well, in next section we describe exactly solution for that. 


## Using custom header codec

Custom header codecs allow you to write any header codec available or extends it by your own functionality. So lets say we would like to extend Authorization header with our own version of Authorization header while still keeping the current Authroization header codec in place. 

Let's say we ahve our own Authorization header case class : 
```scala

case class MyAuthorizationTokenHeader(token: String) extends HttpHeader

```

First we need to create codec that will encode authorization of our own, and then, when that won't pass, we will try to decode with default. This is quite simply achievable by following code snipped: {

```scala
import scodec.codecs._
import spinoco.protocol.http.codec.helper._
import spinoco.procotol.http.header.value.Authorization 

object MyAuthorizationTokenHeader {

  // this is simple codec to decode essentially line `Token sometoken`
  val codec : Codec[MyAuthorizationTokenHeader) = 
  (asciiConstant("Token") ~> (whitespace() ~> utf8String)).xmap(
      { token => MyAuthorizationTokenHeader(token)}
      , _.token
    )
    
  // this is new codec, that will first try to decode by our codec and then if that fails, will use default authroization codec.   
  val customAuthorizationHeader: Codec[HttpHeader] = choice(
     codec.upcast[HttpHeader]
     , Authroization.codec
  )

}

```

Once we have that custom codec setup, we only need to plug it to client and/or server likewise we did for GenericHeader before. For example: 

```scala

import spinoco.protocol.http
import spinoco.protocol.http.codec.HttpHeaderCodec

  
val headerCodec: Codec[HttpHeader]= 
 HttpHeaderCodec.codec(Int.MaxValue, ("Authorization" -> MyAuthorizationTokenHeader.customAuthorizationHeader))
 

http.client(
   requestCodec = HttpRequestHeaderCodec.codec(headerCodec)
   , responseCodec = HttpResponseHeaderCodec.codec(headerCodec)
) map { client => 
 /** your code with client **/
}

http.server(
 bindTo = ??? // your ip where you want bind server to 
   , requestCodec = HttpRequestHeaderCodec.codec(headerCodec)
   , responseCodec = HttpResponseHeaderCodec.codec(headerCodec)
) flatMap { server => 
  /** your code with server **/
}


```

## Summary

Both of these techniques have own advantages and drawbacks. It is up to user to decide whichever suits best. However, as you may see with a little effort you may plug very complex encoding and decoding schemes (even including any binary data) that your application may require. 



