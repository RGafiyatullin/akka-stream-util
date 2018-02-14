package com.github.rgafiyatullin.akka_stream_util.stages

import akka.Done
import akka.actor.ActorRef
import akka.util.Timeout

import scala.concurrent.Future

object Switch {
  final case class To(key: Any)
}

final case class Switch[Key](actorRef: ActorRef) {
  import akka.pattern.ask

  def switch(key: Key)(implicit timeout: Timeout): Future[Done] =
    actorRef.ask(Switch.To(key)).mapTo[Done]
}
