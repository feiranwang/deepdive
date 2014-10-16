package org.deepdive.settings

import org.deepdive.Logging
import scala.util.parsing.combinator.RegexParsers

object FactorFunctionParser extends RegexParsers with Logging {
  def relationOrField = """[\w]+""".r
  def arrayDefinition = """\[\]""".r
  def integer = """[0-9]+""".r
  // def factorFunctionName = "Imply" | "Or" | "And" | "Equal" | "IsTrue"

  def implyFactorFunction = ("Imply" | "IMPLY") ~> "(" ~> rep1sep(factorVariable, ",") <~ ")" ^^ { varList =>
    ImplyFactorFunction(varList)
  }

  def orFactorFunction = ("Or" | "OR") ~> "(" ~> rep1sep(factorVariable, ",") <~ ")" ^^ { varList =>
    OrFactorFunction(varList)
  }

  def xorFactorFunction = ("Xor" | "XOR") ~> "(" ~> rep1sep(factorVariable, ",") <~ ")" ^^ { varList =>
    XorFactorFunction(varList)
  }

  def andFactorFunction = ("And" | "AND") ~> "(" ~> rep1sep(factorVariable, ",") <~ ")" ^^ { varList =>
    AndFactorFunction(varList)
  }

  def equalFactorFunction = ("Equal" | "EQUAL") ~> "(" ~> factorVariable ~ ("," ~> factorVariable) <~ ")" ^^ { 
    case v1 ~ v2 =>
    EqualFactorFunction(List(v1, v2))
  }

  def isTrueFactorFunction = ("IsTrue" | "ISTRUE") ~> "(" ~> factorVariable <~ ")" ^^ { variable =>
    IsTrueFactorFunction(List(variable))
  }

  // def multinomialFactorFunction = ("Multinomial" | "MULTINOMIAL") ~> "(" ~> rep1sep(factorVariable, ",") <~ ")" ^^ { 
  //   case (varList) =>
  //   MultinomialFactorFunction(varList)
  // }

  // multinomial factor with custom cupport
  def multinomialFactorFunction = ("Multinomial" | "MULTINOMIAL") ~> ("(" ~> rep1sep(factorVariable, ",") <~ ")") ~
    ("(" ~> relationOrField <~ ")").? ^^ { 
    case (varList ~ support) =>
      MultinomialFactorFunction(varList, support)
  }

  def treeConstraintFactorFunction = ("TreeConstraint") ~> "(" ~> rep1sep(factorVariable, ",") <~ ")" ^^ { varList =>
    TreeConstraintFactorFunction(varList)
  }

  def parentLabelConstraintFactorFunction = ("ParentLabelConstraint") ~> "(" ~> rep1sep(factorVariable, ",") <~ ")" ^^ { varList =>
    TreeConstraintFactorFunction(varList)
  }

  
  def factorVariable = ("!"?) ~ rep1sep(relationOrField, ".") ~ (arrayDefinition?) ~ 
    (("=" ~> integer)?) ~ (("==" ~> relationOrField)?) ^^ { 
    case (isNegated ~ varList ~ isArray ~ predicate ~ predicateFromData)  => 
      FactorFunctionVariable(varList.take(varList.size - 1).mkString("."), varList.last, 
        isArray.isDefined, isNegated.isDefined, readPredicate(predicate), predicateFromData)
  }

  def readPredicate(predicate: Option[String]) : Option[Long] = {
    predicate match {
      case Some(number) => Some(number.toLong)
      case None => None
    }
  }

  def factorFunc = implyFactorFunction | orFactorFunction | andFactorFunction | 
    equalFactorFunction | isTrueFactorFunction | xorFactorFunction | 
    multinomialFactorFunction | treeConstraintFactorFunction |
    parentLabelConstraintFactorFunction
}