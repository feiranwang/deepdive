package org.deepdive.settings

import scala.util.parsing.combinator.RegexParsers
import org.deepdive.Context

object DataTypeParser extends RegexParsers {
  def CategoricalParser = "Categorical" ~> "(" ~> ("""\d+""".r | "?") ~ (("," ~> """\d+""".r)?) <~ ")" ^^ { 
    case (n ~ m) => 
    n match {
      case "?" => MultinomialType(-1, -1)
      case _ => MultinomialType(n.toInt, m.getOrElse(n).toInt) 
    }
  }
  def BooleanParser = "Boolean" ^^ { s => BooleanType }
  def dataType = CategoricalParser | BooleanParser

}