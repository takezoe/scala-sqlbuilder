package com.github.takezoe.tranquil

import java.sql.{Connection, ResultSet}
import scala.collection.mutable.ListBuffer

class Query[B <: TableDef[_], T, R](
  private val base: B,
  private val definitions: T,
  private val mapper: ResultSet => R,
  private val filters: Seq[Condition] = Nil,
  private val sorts: Seq[Sort] = Nil,
  private val innerJoins: Seq[(Query[_, _, _], Condition)] = Nil,
  private val leftJoins: Seq[(Query[_, _, _], Condition)] = Nil
) {

  private def isTableQuery: Boolean = {
    filters.isEmpty && sorts.isEmpty && innerJoins.isEmpty && leftJoins.isEmpty
  }

  private def getBase: TableDef[_] = base

  def innerJoin[J <: TableDef[K], K](table: Query[J, J, K])(on: (T, J) => Condition): Query[B, (T, J), (R, K)] = {
    new Query[B, (T, J), (R, K)](
      base        = base,
      definitions = (definitions, table.base),
      mapper      = (rs: ResultSet) => (mapper(rs), table.base.toModel(rs)),
      filters     = filters,
      sorts       = sorts,
      innerJoins  = innerJoins :+ (table, on(definitions, table.base)),
      leftJoins   = leftJoins
    )
  }

  def leftJoin[J <: TableDef[K], K](table: Query[J, J, K])(on: (T, J) => Condition): Query[B, (T, J), (R, Option[K])] = {
    new Query[B, (T, J), (R, Option[K])](
      base        = base,
      definitions = (definitions, table.base),
      mapper      = (rs: ResultSet) => (mapper(rs), if(rs.getObject(table.base.columns.head.asName) == null) None else Some(table.base.toModel(rs))),
      filters     = filters,
      sorts       = sorts,
      innerJoins  = innerJoins,
      leftJoins   = leftJoins :+ (table, on(definitions, table.base))
    )
  }

  def filter(condition: T => Condition): Query[B, T, R] = {
    new Query[B, T, R](
      base        = base,
      definitions = definitions,
      mapper      = mapper,
      filters     = filters :+ condition(definitions),
      sorts       = sorts,
      innerJoins  = innerJoins,
      leftJoins   = leftJoins
    )
  }

  def sortBy(orderBy: T => Sort): Query[B, T, R] = {
    new Query[B, T, R](
      base        = base,
      definitions = definitions,
      mapper      = mapper,
      filters     = filters,
      sorts       = sorts :+ orderBy(definitions),
      innerJoins  = innerJoins,
      leftJoins   = leftJoins
    )
  }

//  def map[U, V](mapper: T => Columns[U, V]): Query[B, U, V] = {
//    val columns = mapper(definitions)
//    new Query[B, U, V](
//      base        = base,
//      definitions = columns.definitions,
//      mapper      = columns.toModel _,
//      filters     = filters,
//      sorts       = sorts,
//      innerJoins  = innerJoins,
//      leftJoins   = leftJoins
//    )
//  }

  def selectStatement(bindParams: BindParams = new BindParams(), select: Option[String] = None): (String, BindParams) = {
    val sb = new StringBuilder()
    sb.append("SELECT ")

    select match {
      case Some(x) => sb.append(x)
      case None => {
        val columns = base.columns.map(c => c.fullName + " AS " + c.asName) ++
          innerJoins.flatMap { case (query, _) => query.getBase.columns.map(c => c.fullName + " AS " + c.asName) } ++
          leftJoins.flatMap  { case (query, _) => query.getBase.columns.map(c => c.fullName + " AS " + c.asName) }
        sb.append(columns.mkString(", "))
      }
    }

    sb.append(" FROM ")
    sb.append(base.tableName)
    sb.append(" ")
    sb.append(base.alias.get)

    innerJoins.foreach { case (query, condition) =>
      sb.append(" INNER JOIN ")
      if(query.isTableQuery){
        sb.append(query.getBase.tableName)
      } else {
        sb.append("(")
        sb.append(query.selectStatement(bindParams)._1)
        sb.append(")")
      }
      sb.append(" ")
      sb.append(query.getBase.alias.get)
      sb.append(" ON ")
      sb.append(condition.sql)
      bindParams ++= condition.parameters
    }

    leftJoins.foreach { case (query, condition) =>
      sb.append(" LEFT JOIN ")
      if(query.isTableQuery){
        sb.append(query.getBase.tableName)
      } else {
        sb.append("(")
        sb.append(query.selectStatement(bindParams))
        sb.append(")")
      }
      sb.append(" ")
      sb.append(query.getBase.alias.get)
      sb.append(" ON ")
      sb.append(condition.sql)
    }

    if(filters.nonEmpty){
      sb.append(" WHERE ")
      sb.append(filters.map(_.sql).mkString(" AND "))
      bindParams ++= filters.flatMap(_.parameters)
    }
    if(sorts.nonEmpty){
      sb.append(" ORDER BY ")
      sb.append(sorts.map(_.sql).mkString(", "))
    }

    (sb.toString(), bindParams)
  }

  // TODO It's possible to optimize the query for getting count.
  def count(conn: Connection): Int = {
    val (sql: String, bindParams: BindParams) = selectStatement(select = Some("COUNT(*) AS COUNT"))
    using(conn.prepareStatement(sql)){ stmt =>
      bindParams.params.zipWithIndex.foreach { case (param, i) => param.set(stmt, i) }
      using(stmt.executeQuery()){ rs =>
        rs.next
        rs.getInt("COUNT")
      }
    }
  }

  def list(conn: Connection): Seq[R] = {
    val (sql, bindParams) = selectStatement()
    using(conn.prepareStatement(sql)){ stmt =>
      bindParams.params.zipWithIndex.foreach { case (param, i) => param.set(stmt, i) }
      using(stmt.executeQuery()){ rs =>
        val list = new ListBuffer[R]
        while(rs.next){
          list += mapper(rs)
        }
        list.toSeq
      }
    }
  }

  def first(conn: Connection): R = {
    firstOption(conn).getOrElse {
      throw new NoSuchElementException()
    }
  }

  def firstOption(conn: Connection): Option[R] = {
    val (sql, bindParams) = selectStatement()
    using(conn.prepareStatement(sql)){ stmt =>
      bindParams.params.zipWithIndex.foreach { case (param, i) => param.set(stmt, i) }
      using(stmt.executeQuery()) { rs =>
        if (rs.next) {
          Some(mapper(rs))
        } else {
          None
        }
      }
    }
  }

}
