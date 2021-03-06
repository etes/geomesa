/***********************************************************************
* Copyright (c) 2013-2016 Commonwealth Computer Research, Inc.
* All rights reserved. This program and the accompanying materials
* are made available under the terms of the Apache License, Version 2.0
* which accompanies this distribution and is available at
* http://www.opensource.org/licenses/apache2.0.php.
*************************************************************************/

package org.locationtech.geomesa.api

import java.lang.Iterable
import java.util
import java.util.{Date, List => JList}

import com.github.benmanes.caffeine.cache.{CacheLoader, Caffeine}
import com.vividsolutions.jts.geom.Geometry
import org.apache.accumulo.core.client.Connector
import org.apache.hadoop.classification.InterfaceStability
import org.geotools.data.simple.SimpleFeatureWriter
import org.geotools.data.{DataStoreFinder, Transaction}
import org.geotools.factory.Hints
import org.geotools.filter.identity.FeatureIdImpl
import org.geotools.filter.text.ecql.ECQL
import org.locationtech.geomesa.accumulo.data.{AccumuloDataStore, AccumuloDataStoreParams}
import org.locationtech.geomesa.curve.TimePeriod
import org.locationtech.geomesa.security.SecurityUtils
import org.locationtech.geomesa.utils.geotools.SftBuilder
import org.locationtech.geomesa.utils.stats.Cardinality
import org.locationtech.geomesa.utils.uuid.Z3UuidGenerator
import org.opengis.feature.simple.SimpleFeature

import scala.collection.JavaConverters._

@InterfaceStability.Unstable
class AccumuloGeoMesaIndex[T](protected val ds: AccumuloDataStore,
                              name: String,
                              serde: ValueSerializer[T],
                              view: SimpleFeatureView[T]
                             ) extends GeoMesaIndex[T] {

  val sft = AccumuloGeoMesaIndex.buildSimpleFeatureType(name)(view)

  if (!ds.getTypeNames.contains(sft.getTypeName)) {
    ds.createSchema(sft)
  }

  val fs = ds.getFeatureSource(sft.getTypeName)

  val writers =
    Caffeine.newBuilder().build(
      new CacheLoader[String, SimpleFeatureWriter] {
        override def load(k: String): SimpleFeatureWriter = {
          ds.getFeatureWriterAppend(k, Transaction.AUTO_COMMIT).asInstanceOf[SimpleFeatureWriter]
        }
      })

  override def query(query: GeoMesaQuery): Iterable[T] = {
    import org.locationtech.geomesa.utils.geotools.Conversions._

    import scala.collection.JavaConverters._

    fs.getFeatures(query.getFilter)
      .features()
      .map { f => serde.fromBytes(f.getAttribute(1).asInstanceOf[Array[Byte]]) }
      .toIterable.asJava
  }

  override def insert(id: String, value: T, geometry: Geometry, dtg: Date): String = {
    insert(id, value, geometry, dtg, null)
  }

  override def insert(value: T, geom: Geometry, dtg: Date): String = {
    val id = Z3UuidGenerator.createUuid(geom, dtg.getTime, TimePeriod.Week).toString
    insert(id, value, geom, dtg, null)
  }

  override def insert(id: String, value: T, geom: Geometry, dtg: Date, hints: util.Map[String, AnyRef]): String = {
    val bytes = serde.toBytes(value)
    val fw = writers.get(sft.getTypeName)
    val sf = fw.next()
    sf.getUserData.put(Hints.USE_PROVIDED_FID, java.lang.Boolean.TRUE)
    sf.setAttribute("geom", geom)
    sf.setAttribute("dtg", dtg)
    sf.setAttribute("payload", bytes)
    sf.getIdentifier.asInstanceOf[FeatureIdImpl].setID(id)
    view.populate(sf, value, id, bytes, geom, dtg)
    setVisibility(sf, hints)
    fw.write()
    id
  }

  private def setVisibility(sf: SimpleFeature, hints: util.Map[String, AnyRef]): Unit = {
    if (hints != null && hints.containsKey(AccumuloGeoMesaIndex.VISIBILITY)) {
      val viz = hints.get(AccumuloGeoMesaIndex.VISIBILITY)
      sf.getUserData.put(SecurityUtils.FEATURE_VISIBILITY, viz)
    }
  }

  override def supportedIndexes(): Array[IndexType] =
    Array(IndexType.SPATIOTEMPORAL, IndexType.SPATIAL, IndexType.RECORD)

  override def update(id: String, newValue: T, geometry: Geometry, dtg: Date): Unit = ???

  override def delete(id: String): Unit = fs.removeFeatures(ECQL.toFilter(s"IN('$id')"))

  // should remove the index (SFT) as well as the associated Accumulo tables, if appropriate
  override def removeSchema(): Unit = ds.removeSchema(sft.getTypeName)

  override def flush(): Unit = {
    // DO NOTHING - using AUTO_COMMIT
  }

  override def close(): Unit = {
    import scala.collection.JavaConversions._

    writers.asMap().values().foreach {
      _.close()
    }
    ds.dispose()
  }

  def catalogTable() = ds.config.catalog
}

@InterfaceStability.Unstable
object AccumuloGeoMesaIndex {
  def build[T](name: String,
               connector: Connector,
               valueSerializer: ValueSerializer[T]): AccumuloGeoMesaIndex[T] = {
    build(name, connector, valueSerializer, new DefaultSimpleFeatureView[T]())
  }

  def build[T](name: String,
               connector: Connector,
               valueSerializer: ValueSerializer[T],
               view: SimpleFeatureView[T]): AccumuloGeoMesaIndex[T] =
    buildWithView[T](name, connector, valueSerializer, view)

  def build[T](name: String,
               zk: String,
               instanceId: String,
               user: String, pass: String,
               mock: Boolean,
               valueSerializer: ValueSerializer[T])
              (view: SimpleFeatureView[T] = new DefaultSimpleFeatureView[T]()) =
    buildWithView[T](name, zk, instanceId, user, pass, mock, valueSerializer, view)

  def buildWithView[T](name: String,
                       zk: String,
                       instanceId: String,
                       user: String, pass: String,
                       mock: Boolean,
                       valueSerializer: ValueSerializer[T],
                       view: SimpleFeatureView[T]) = {
    import scala.collection.JavaConversions._
    val ds =
      DataStoreFinder.getDataStore(Map(
        AccumuloDataStoreParams.tableNameParam.key -> name,
        AccumuloDataStoreParams.zookeepersParam.key -> zk,
        AccumuloDataStoreParams.instanceIdParam.key -> instanceId,
        AccumuloDataStoreParams.userParam.key -> user,
        AccumuloDataStoreParams.passwordParam.key -> pass,
        AccumuloDataStoreParams.mockParam.key -> (if (mock) "TRUE" else "FALSE")
      )).asInstanceOf[AccumuloDataStore]
    new AccumuloGeoMesaIndex[T](ds, name, valueSerializer, view)
  }

  def buildWithView[T](name: String,
                       connector: Connector,
                       valueSerializer: ValueSerializer[T],
                       view: SimpleFeatureView[T]) = {

    val ds = DataStoreFinder.getDataStore(
      Map[String, java.io.Serializable](
        AccumuloDataStoreParams.connParam.key -> connector.asInstanceOf[java.io.Serializable],
        AccumuloDataStoreParams.tableNameParam.key -> name
      ).asJava).asInstanceOf[AccumuloDataStore]
    new AccumuloGeoMesaIndex[T](ds, name, valueSerializer, view)
  }

  def buildDefaultView[T](name: String,
                          zk: String,
                          instanceId: String,
                          user: String, pass: String,
                          mock: Boolean,
                          valueSerializer: ValueSerializer[T]) = {
    build(name, zk, instanceId, user, pass, mock, valueSerializer)()
  }

  def buildDefaultView[T](name: String,
                          connector: Connector,
                          valueSerializer: ValueSerializer[T]) = {
    build(name, connector, valueSerializer, new DefaultSimpleFeatureView[T]())
  }

  private def buildSimpleFeatureType[T](name: String)
                                       (view: SimpleFeatureView[T] = new DefaultSimpleFeatureView[T]()) = {
    val builder = new SftBuilder()
      .date("dtg", index = false, default = true)
      .bytes("payload", SftBuilder.Opts(index = false, stIndex = false, default = false, Cardinality.UNKNOWN))
      .geometry("geom", default = true)
      .userData("geomesa.mixed.geometries", "true")

    view.getExtraAttributes.asScala.foreach {
      builder.attributeDescriptor
    }

    builder.build(name)
  }

  final val VISIBILITY = "visibility"
}
