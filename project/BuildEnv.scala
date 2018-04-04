
import sbt._

object BuildEnv {
  val scalaVersion: String = sys.env.getOrElse("SCALA_VERSION", "2.12.4")
  val version: String = sys.env.getOrElse("VERSION", s"0.0.0.${System.currentTimeMillis()}-SNAPSHOT")
  val publishTo: Option[MavenRepository] =
    for {
      repoName <- sys.env.get("PUBLISH_REPO_NAME")
      repoLocation <- sys.env.get("PUBLISH_REPO_LOCATION")
    }
      yield repoName at repoLocation

  val credentials: Option[Credentials] =
    for {
      realm <- sys.env.get("PUBLISH_REALM")
      host <- sys.env.get("PUBLISH_HOST")
      user <- sys.env.get("PUBLISH_USER")
      password <- sys.env.get("PUBLISH_PASSWORD")
    }
      yield Credentials(realm, host, user, password)
}

