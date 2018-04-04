
lazy val root = (project in file("."))
  .settings(
    name := "akka-stream-util",
    organization := "com.github.rgafiyatullin",
    version := BuildEnv.version,

    scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature"),
    scalacOptions ++= Seq("-language:implicitConversions"),
    scalacOptions ++= Seq("-Ywarn-value-discard", "-Xfatal-warnings"),
    scalacOptions ++= (BuildEnv.scalaVersion match {
      case v2_11 if v2_11.startsWith("2.11.") => Seq("-language:existentials")
      case v2_12 if v2_12.startsWith("2.12.") => Seq.empty
    }),

    scalaVersion := BuildEnv.scalaVersion,

    libraryDependencies ++= Seq(
      "org.scalatest" %% "scalatest" % {
        scalaVersion.value match {
          case v2_12 if v2_12.startsWith("2.12.") => "3.0.4"
          case v2_11 if v2_11.startsWith("2.11.") => "2.2.6"
        }
      },
      "com.typesafe.akka"             %% "akka-stream"    % "2.5.7"
    ),

    publishTo := BuildEnv.publishTo,
    credentials ++= BuildEnv.credentials.toSeq
  )

