package org.deepdive.settings

/* Calibration Settings */
case class CalibrationSettings(holdoutFraction: Double, holdoutQuery: Option[String], observationQuery: Option[String],
  cfgRuleQuery: Option[String] = None)