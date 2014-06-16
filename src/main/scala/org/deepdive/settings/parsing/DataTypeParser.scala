package org.deepdive.settings

import scala.util.parsing.combinator.RegexParsers
import org.deepdive.Context

object DataTypeParser extends RegexParsers {
  def CategoricalParser = "Categorical" ~> "(" ~> ("""\d+""".r | "?") <~ ")" ^^ { n => 
    n match {
      case "?" => MultinomialType(-1)
      case n => MultinomialType(n.toInt) 
    }
  }
  def BooleanParser = "Boolean" ^^ { s => BooleanType }
  def dataType = CategoricalParser | BooleanParser

}