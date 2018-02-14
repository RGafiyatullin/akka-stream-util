package com.github.rgafiyatullin.akka_stream_util.stages

import akka.stream.{Attributes, Inlet, Outlet}
import com.github.rgafiyatullin.akka_stream_util.custom_stream_stage.Stage
import com.github.rgafiyatullin.akka_stream_util.stages.switch_out.{SwitchOutShape, SwitchOutState}

import scala.concurrent.{Future, Promise}

object SwitchOut {
  final case class Keys[Key](keys: Seq[Key]) {
    require(keys.nonEmpty)

    def of[Item]: SwitchOut[Key, Item] =
      SwitchOut(keys.head,
        SwitchOutShape(
          Inlet("In"),
          keys
            .map { k =>
              k -> Outlet[Item]("Out:" + k.toString) }
            .toMap))
  }

  def keys[Key](keys: Key*): Keys[Key] =
    Keys(keys)
}

final case class SwitchOut[Key, Item] private
  (initialActiveKey: Key,
   shape: SwitchOutShape[Key, Item])
  extends Stage[SwitchOut[Key, Item]]
{
  override type Shape = SwitchOutShape[Key, Item]
  override type State = SwitchOutState[Key, Item]
  override type MatValue = Future[Switch[Key]]

  override def initialStateAndMatValue
    (logic: Stage.RunnerLogic,
     inheritedAttributes: Attributes)
  : (State, MatValue) = {
    val apiPromise = Promise[Switch[Key]]()
    val state = SwitchOutState.create(initialActiveKey, shape, apiPromise)
    (state, apiPromise.future)
  }
}
