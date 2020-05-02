

val ReleaseTag = """^release/([\d\.]+a?)$""".r

lazy val contributors = Seq(
 "pchlupacek" -> "Pavel Chlupáček"
)


lazy val commonSettings = Seq(
   organization := "com.spinoco",
   scalaVersion := "2.12.11",
   crossScalaVersions := Seq("2.12.11", "2.13.0"),
   scalacOptions ++= Seq(
    "-feature",
    "-deprecation",
    "-language:implicitConversions",
    "-language:higherKinds",
    "-language:existentials",
    "-language:postfixOps",
    "-Xfatal-warnings",
    "-Ywarn-value-discard"
   ),
   scalacOptions ++= {
     CrossVersion.partialVersion(scalaVersion.value) match {
       case Some((2, v)) if v >= 13 =>
         Seq("-Ymacro-annotations", "-Ywarn-unused:imports")
       case _ =>
         Seq("-Yno-adapted-args", "-Ywarn-unused-import")
     }
   },
   scalacOptions in (Compile, console) ~= {_.filterNot("-Ywarn-unused-import" == _)},
   libraryDependencies ++= Seq(
//     "com.github.mpilquist" %% "simulacrum" % "0.13.0"
     "org.scalacheck" %% "scalacheck" % "1.14.3" % "test"
     , "com.spinoco" %% "protocol-http" % "0.4.0-M1"
     , "com.spinoco" %% "protocol-websocket" % "0.4.0-M1"
     , "co.fs2" %% "fs2-core" % "2.3.0"
     , "co.fs2" %% "fs2-io" % "2.3.0"
   ),
   libraryDependencies ++= {
     CrossVersion.partialVersion(scalaVersion.value) match {
       case Some((2, v)) if v <= 12 =>
         Seq(
           compilerPlugin("org.scalamacros" % "paradise" % "2.1.1" cross CrossVersion.full)
         )
       case _ =>
         // if scala 2.13.0-M4 or later, macro annotations merged into scala-reflect
         // https://github.com/scala/scala/pull/6606
         Nil
     }
   },
   scmInfo := Some(ScmInfo(url("https://github.com/Spinoco/fs2-http"), "git@github.com:Spinoco/fs2-http.git")),
   homepage := None,
   licenses += ("MIT", url("http://opensource.org/licenses/MIT")),
   initialCommands := s"""
   import fs2._
   import fs2.util.syntax._
   import spinoco.fs2.http
   import http.Resources._
   import spinoco.protocol.http.header._
  """
) ++ testSettings ++ scaladocSettings ++ publishingSettings ++ releaseSettings

lazy val testSettings = Seq(
  parallelExecution in Test := false,
  testOptions in Test += Tests.Argument(TestFrameworks.ScalaTest, "-oDF"),
  publishArtifact in Test := true
)

lazy val scaladocSettings = Seq(
   scalacOptions in (Compile, doc) ++= Seq(
    "-doc-source-url", scmInfo.value.get.browseUrl + "/tree/master€{FILE_PATH}.scala",
    "-sourcepath", baseDirectory.in(LocalRootProject).value.getAbsolutePath,
    "-implicits",
    "-implicits-show-all"
  ),
   scalacOptions in (Compile, doc) ~= { _ filterNot { _ == "-Xfatal-warnings" } },
   autoAPIMappings := true
)

lazy val publishingSettings = Seq(
  publishTo := {
   val nexus = "https://oss.sonatype.org/"
   if (version.value.trim.endsWith("SNAPSHOT"))
     Some("snapshots" at nexus + "content/repositories/snapshots")
   else
     Some("releases" at nexus + "service/local/staging/deploy/maven2")
  },
  credentials ++= (for {
   username <- Option(System.getenv().get("SONATYPE_USERNAME"))
   password <- Option(System.getenv().get("SONATYPE_PASSWORD"))
  } yield Credentials("Sonatype Nexus Repository Manager", "oss.sonatype.org", username, password)).toSeq,
  publishMavenStyle := true,
  pomIncludeRepository := { _ => false },
  pomExtra := {
    <url>https://github.com/Spinoco/fs2-http</url>
    <developers>
      {for ((username, name) <- contributors) yield
      <developer>
        <id>{username}</id>
        <name>{name}</name>
        <url>http://github.com/{username}</url>
      </developer>
      }
    </developers>
  },
  pomPostProcess := { node =>
   import scala.xml._
   import scala.xml.transform._
   def stripIf(f: Node => Boolean) = new RewriteRule {
     override def transform(n: Node) =
       if (f(n)) NodeSeq.Empty else n
   }
   val stripTestScope = stripIf { n => n.label == "dependency" && (n \ "scope").text == "test" }
   new RuleTransformer(stripTestScope).transform(node)(0)
  }
  , resolvers += Resolver.mavenLocal
)

lazy val releaseSettings = Seq(
  releaseCrossBuild := true
)

lazy val `fs2-http`=
  project.in(file("./"))
  .settings(commonSettings)
  .settings(
    name := "fs2-http"
  )


