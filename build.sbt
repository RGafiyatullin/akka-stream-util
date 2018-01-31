name := "akka-stream-util"

organization := "com.github.rgafiyatullin"

version := "0.1.3.0"

scalaVersion := "2.12.4"

scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature")
scalacOptions ++= Seq("-language:implicitConversions")
scalacOptions ++= Seq("-Ywarn-value-discard", "-Xfatal-warnings")


libraryDependencies ++= Seq(
  "org.scalatest"                 %% "scalatest"      % "3.0.4",
  "com.typesafe.akka"             %% "akka-stream"    % "2.5.7"
)
