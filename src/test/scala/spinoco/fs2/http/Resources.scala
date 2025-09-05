package spinoco.fs2.http

import cats.effect.IO
import fs2.io.net.Network
import fs2.io.net.tls.TLSContext


object Resources {
  implicit val IORuntime: cats.effect.unsafe.IORuntime = cats.effect.unsafe.IORuntime.global
  implicit val network: Network[IO] = Network.forIO
  implicit val tlsContext: TLSContext[IO] = network.tlsContext.system.unsafeRunSync()

}
