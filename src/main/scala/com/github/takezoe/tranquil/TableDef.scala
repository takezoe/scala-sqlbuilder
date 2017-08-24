package com.github.takezoe.tranquil

import java.sql.ResultSet

/**
 * A base class for table definitions which are used for DSL.
 */
abstract class TableDef[R <: Product](val tableName: String) extends Product {

  lazy val columns: Seq[ColumnBase[_, _]] = getClass.getDeclaredFields.collect { case field
    if classOf[ColumnBase[_, _]].isAssignableFrom(field.getType) =>
      field.setAccessible(true)
      field.get(this).asInstanceOf[ColumnBase[_, _]]
  }.toSeq

  val alias: Option[String]

  val prefix: Option[String]

  def wrap(alias: String): this.type = {
    val constructor = getClass.getDeclaredConstructor(classOf[Option[String]], classOf[Option[String]])
    constructor.newInstance(Option(alias), this.alias.map(_ + "_")).asInstanceOf[this.type]
  }

  def toModel(rs: ResultSet): R

  def fromModel(model: R): Seq[Any] = model.productIterator.toSeq

}

/**
 * Represent a shape of a table. It's used for assemble SQL.
 */
abstract class TableShape[T](table: T){
  val definitions: T = table
  val columns: Seq[ColumnBase[_, _]]
  def wrap(alias: String): TableShape[T]
}

/**
 * Get a TableShape from a table object.
 */
trait TableShapeOf[T] {
  def apply(table: T): TableShape[T]
}
