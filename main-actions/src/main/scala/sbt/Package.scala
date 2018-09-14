/*
 * sbt
 * Copyright 2011 - 2018, Lightbend, Inc.
 * Copyright 2008 - 2010, Mark Harrah
 * Licensed under Apache License 2.0 (see LICENSE)
 */

package sbt

import java.io.File
import java.util.jar.{ Attributes, Manifest }
import scala.collection.JavaConverters._
import sbt.internal.util.Types.:+:
import sbt.io.IO

import sjsonnew.JsonFormat

import sbt.util.Logger

import sbt.util.{ CacheStoreFactory, FilesInfo, ModifiedFileInfo, PlainFileInfo }
import sbt.internal.util.HNil
import sbt.internal.util.HListFormats._
import sbt.util.FileInfo.{ exists, lastModified }
import sbt.util.CacheImplicits._
import sbt.util.Tracked.{ inputChanged, outputChanged }

sealed trait PackageOption

/**
  * == Package ==
  *
  * This module provides an API to package jar files.
  *
  * @see [[https://docs.oracle.com/javase/tutorial/deployment/jar/index.html]]
  */
object Package {
  final case class JarManifest(m: Manifest) extends PackageOption {
    assert(m != null)
  }
  final case class MainClass(mainClassName: String) extends PackageOption
  final case class ManifestAttributes(attributes: (Attributes.Name, String)*) extends PackageOption
  def ManifestAttributes(attributes: (String, String)*): ManifestAttributes = {
    val converted = for ((name, value) <- attributes) yield (new Attributes.Name(name), value)
    new ManifestAttributes(converted: _*)
  }

  def mergeAttributes(a1: Attributes, a2: Attributes) = a1.asScala ++= a2.asScala
  // merges `mergeManifest` into `manifest` (mutating `manifest` in the process)
  def mergeManifests(manifest: Manifest, mergeManifest: Manifest): Unit = {
    mergeAttributes(manifest.getMainAttributes, mergeManifest.getMainAttributes)
    val entryMap = manifest.getEntries.asScala
    for ((key, value) <- mergeManifest.getEntries.asScala) {
      entryMap.get(key) match {
        case Some(attributes) => mergeAttributes(attributes, value)
        case None             => entryMap put (key, value)
      }
    }
  }

  /**
    * The jar package configuration. Contains all relevant information to create a jar file.
    *
    * @param sources the jar contents
    * @param jar the destination jar file
    * @param options additional package information, e.g. jar manifest, main class or manifest attributes
    */
  final class Configuration(
      val sources: Seq[(File, String)],
      val jar: File,
      val options: Seq[PackageOption]
  )

  /**
    *
    * @param conf the package configuration that should be build
    * @param cacheStoreFactory used for jar caching. We try to avoid rebuilds as much as possible
    * @param log feedback for the user
    */
  def apply(conf: Configuration, cacheStoreFactory: CacheStoreFactory, log: Logger): Unit = {
    val manifest = new Manifest
    val main = manifest.getMainAttributes
    for (option <- conf.options) {
      option match {
        case JarManifest(mergeManifest)          => mergeManifests(manifest, mergeManifest)
        case MainClass(mainClassName)            => main.put(Attributes.Name.MAIN_CLASS, mainClassName)
        case ManifestAttributes(attributes @ _*) => main.asScala ++= attributes
        case _                                   => log.warn("Ignored unknown package option " + option)
      }
    }
    setVersion(main)

    type Inputs = Seq[(File, String)] :+: FilesInfo[ModifiedFileInfo] :+: Manifest :+: HNil
    val cachedMakeJar = inputChanged(cacheStoreFactory make "inputs") {
      (inChanged, inputs: Inputs) =>
        import exists.format
        val sources :+: _ :+: manifest :+: HNil = inputs
        outputChanged(cacheStoreFactory make "output") { (outChanged, jar: PlainFileInfo) =>
          if (inChanged || outChanged) {
            makeJar(sources, jar.file, manifest, log)
            jar.file
          } else
            log.debug("Jar uptodate: " + jar.file)
        }
    }

    val inputFiles = conf.sources.map(_._1).toSet
    val inputs = conf.sources :+: lastModified(inputFiles) :+: manifest :+: HNil
    cachedMakeJar(inputs)(() => exists(conf.jar))
  }

  /**
    * updates the manifest version is there is none present.
    *
    * @param main the current jar attributes
    */
  def setVersion(main: Attributes): Unit = {
    val version = Attributes.Name.MANIFEST_VERSION
    if (main.getValue(version) eq null) {
      main.put(version, "1.0")
      ()
    }
  }
  def addSpecManifestAttributes(name: String, version: String, orgName: String): PackageOption = {
    import Attributes.Name._
    val attribKeys = Seq(SPECIFICATION_TITLE, SPECIFICATION_VERSION, SPECIFICATION_VENDOR)
    val attribVals = Seq(name, version, orgName)
    ManifestAttributes(attribKeys zip attribVals: _*)
  }
  def addImplManifestAttributes(
      name: String,
      version: String,
      homepage: Option[java.net.URL],
      org: String,
      orgName: String
  ): PackageOption = {
    import Attributes.Name._

    // The ones in Attributes.Name are deprecated saying:
    //   "Extension mechanism will be removed in a future release. Use class path instead."
    val IMPLEMENTATION_VENDOR_ID = new Attributes.Name("Implementation-Vendor-Id")
    val IMPLEMENTATION_URL = new Attributes.Name("Implementation-URL")

    val attribKeys = Seq(
      IMPLEMENTATION_TITLE,
      IMPLEMENTATION_VERSION,
      IMPLEMENTATION_VENDOR,
      IMPLEMENTATION_VENDOR_ID,
    )
    val attribVals = Seq(name, version, orgName, org)
    ManifestAttributes((attribKeys zip attribVals) ++ {
      homepage map (h => (IMPLEMENTATION_URL, h.toString))
    }: _*)
  }
  def makeJar(sources: Seq[(File, String)], jar: File, manifest: Manifest, log: Logger): Unit = {
    val path = jar.getAbsolutePath
    log.info("Packaging " + path + " ...")
    if (jar.exists)
      if (jar.isFile)
        IO.delete(jar)
      else
        sys.error(path + " exists, but is not a regular file")
    log.debug(sourcesDebugString(sources))
    IO.jar(sources, jar, manifest)
    log.info("Done packaging.")
  }
  def sourcesDebugString(sources: Seq[(File, String)]): String =
    "Input file mappings:\n\t" + (sources map { case (f, s) => s + "\n\t  " + f } mkString ("\n\t"))

  implicit def manifestFormat: JsonFormat[Manifest] = projectFormat[Manifest, Array[Byte]](
    m => {
      val bos = new java.io.ByteArrayOutputStream()
      m write bos
      bos.toByteArray
    },
    bs => new Manifest(new java.io.ByteArrayInputStream(bs))
  )
}
