package com.github.rgafiyatullin.akka_stream_util.stages

import akka.stream.{Attributes, Inlet, Outlet}
import com.github.rgafiyatullin.akka_stream_util.custom_stream_stage.Stage
import com.github.rgafiyatullin.akka_stream_util.stages.switch_in.{SwitchInShape, SwitchInState}

import scala.concurrent.{Future, Promise}

object SwitchIn {
  final case class Keys[Key](keys: Seq[Key]) {
    require(keys.nonEmpty)

    def of[Item]: SwitchIn[Key, Item] =
      SwitchIn(
        keys.head,
        SwitchInShape(
          keys
            .map { key =>
              key -> Inlet[Item]("In:" + key.toString)
            }
            .toMap,
            Outlet[Item]("Out")))
  }

  def keys[Key](keys: Key*): Keys[Key] =
    Keys(keys)

}

final case class SwitchIn[Key, Item] private
  (initialActiveKey: Key,
   shape: SwitchInShape[Key, Item])
  extends Stage[SwitchIn[Key, Item]]
{
  override type Shape = SwitchInShape[Key, Item]
  override type State = SwitchInState[Key, Item]
  override type MatValue = Future[Switch[Key]]

  override def initialStateAndMatValue
    (logic: Stage.RunnerLogic,
     inheritedAttributes: Attributes)
  : (State, MatValue) =
    {
      val apiPromise = Promise[Switch[Key]]()
      val initialState = SwitchInState.create(shape, initialActiveKey, apiPromise)
      (initialState,  apiPromise.future)
    }
}
