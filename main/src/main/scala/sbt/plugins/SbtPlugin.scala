/*
 * sbt
 * Copyright 2011 - 2018, Lightbend, Inc.
 * Copyright 2008 - 2010, Mark Harrah
 * Licensed under Apache License 2.0 (see LICENSE)
 */

package sbt
package plugins

import Keys._

object SbtPlugin extends AutoPlugin {
  override def requires = ScriptedPlugin

  override lazy val projectSettings = Seq(
    sbtPlugin := true
  )
}
