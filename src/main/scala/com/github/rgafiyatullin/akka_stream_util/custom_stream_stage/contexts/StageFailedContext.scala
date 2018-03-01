package com.github.rgafiyatullin.akka_stream_util.custom_stream_stage.contexts

import com.github.rgafiyatullin.akka_stream_util.custom_stream_stage.Stage

object StageFailedContext {
  def create[Stg <: Stage[Stg]]
    (internals: Context.Internals[Stg],
     reason: Throwable)
  : StageFailedContext[Stg] =
    StageFailedContext(internals, reason)
}

final case class StageFailedContext[Stg <: Stage[Stg]]
  (internals: Context.Internals[Stg],
   reason: Throwable)
    extends Context[StageFailedContext[Stg], Stg]
{
  override def withInternals(i: Context.Internals[Stg]): StageFailedContext[Stg] =
    copy(internals = i)
}
