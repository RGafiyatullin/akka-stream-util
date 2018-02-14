package com.github.rgafiyatullin.akka_stream_util.stages.switch_out

import akka.stream.{Inlet, Outlet, Shape}

import scala.collection.immutable

final case class SwitchOutShape[Key, Item](inlet: Inlet[Item], outletMap: Map[Key, Outlet[Item]]) extends Shape {
  override def inlets: immutable.Seq[Inlet[_]] = List(inlet)
  override def outlets: immutable.Seq[Outlet[_]] = outletMap.values.toList

  def in: Inlet[Item] = inlet
  def out(key: Key): Outlet[Item] = outletMap(key)

  override def deepCopy(): Shape =
    SwitchOutShape(
      inlet.carbonCopy(),
      outletMap.map { case (k, v) => k -> v.carbonCopy() })
}
