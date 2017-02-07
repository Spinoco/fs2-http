package spinoco.fs2.http.sse

import fs2._
import org.scalacheck.Properties
import org.scalacheck.Prop._
import scodec.bits.ByteVector
import spinoco.fs2.http.sse.SSEMessage.SSEData
import spinoco.fs2.interop.scodec.ByteVectorChunk
import spinoco.fs2.http.util.chunk2ByteVector

object SSEEncodingSpec extends Properties("SSEEncoding") {


  property("encode") = secure {
    Stream(
      SSEMessage.SSEData(Seq("data1"), None, None)
      , SSEMessage.SSEData(Seq("data2", "data3"), None, None)
      , SSEMessage.SSEData(Seq("data4"), Some("event1"), None)
      , SSEMessage.SSEData(Seq("data5"), None, Some("id1"))
      , SSEMessage.SSEData(Seq("data6"), Some("event2"), Some("id2"))
    ).through(SSEEncoding.encode[Task]).chunks.runLog.map { _ map chunk2ByteVector reduce (_ ++ _) decodeUtf8  }.unsafeRun ?=
    Right(
    "data: data1\n\ndata: data2\ndata: data3\n\nevent: event1\ndata: data4\n\ndata: data5\nid: id1\n\nevent: event2\ndata: data6\nid: id2\n\n"
    )

  }

  property("decode.example.1") = secure {

    Stream.chunk(ByteVectorChunk(ByteVector.view(
      ": test stream\n\ndata: first event\nid: 1\n\ndata:second event\nid\n\ndata:  third event".getBytes()
    )))
    .through(SSEEncoding.decode[Task]).runLog.unsafeRun ?=
    Vector(
      SSEData(Vector("first event"), None, Some("1"))
      , SSEData(Vector("second event"), None, Some(""))
    )

  }

  property("decode.example.2") = secure {

    Stream.chunk(ByteVectorChunk(ByteVector.view(
      "data\n\ndata\ndata\n\ndata:".getBytes()
    )))
      .through(SSEEncoding.decode[Task]).runLog.unsafeRun ?=
      Vector(
        SSEData(Vector(""), None, None)
        , SSEData(Vector("",""), None, None)
      )

  }


  property("decode.example.3") = secure {

    Stream.chunk(ByteVectorChunk(ByteVector.view(
      "data:test\n\ndata: test\n\n".getBytes()
    )))
      .through(SSEEncoding.decode[Task]).runLog.unsafeRun ?=
      Vector(
        SSEData(Vector("test"), None, None)
        , SSEData(Vector("test"), None, None)
      )

  }


}
