package org.deepdive.inference

import java.io.{File, PrintWriter}
import org.deepdive.calibration._
import org.deepdive.datastore.JdbcDataStore
import org.deepdive.Logging
import org.deepdive.settings._
import org.deepdive.Context
import play.api.libs.json._
import scalikejdbc._
import scala.util.matching._
import scala.io.Source
import scala.util.Random
import scala.sys.process._
import scala.util.{Try, Success, Failure}
// import scala.collection.mutable.Map


/* Stores the factor graph and inference results. */
trait SQLInferenceDataStoreComponent extends InferenceDataStoreComponent {

  def inferenceDataStore : SQLInferenceDataStore

}

trait SQLInferenceDataStore extends InferenceDataStore with Logging {

  def ds : JdbcDataStore

  val factorOffset = new java.util.concurrent.atomic.AtomicLong(0)

  /* Internal Table names */
  def WeightsTable = "dd_graph_weights"
  def lastWeightsTable = "dd_graph_last_weights"
  def FactorsTable = "dd_graph_factors"
  def VariablesTable = "dd_graph_variables"
  def VariablesMapTable = "dd_graph_variables_map"
  def WeightResultTable = "dd_inference_result_weights"
  def VariablesHoldoutTable = "dd_graph_variables_holdout"
  def VariableResultTable = "dd_inference_result_variables"
  def MappedInferenceResultView = "dd_mapped_inference_result"
  def IdSequence = "id_sequence"

  def WeightsTableTemp = "weights_temp"


  def unwrapSQLType(x: Any) : Any = {
    x match {
      case x : org.postgresql.jdbc4.Jdbc4Array => x.getArray().asInstanceOf[Array[_]].toList
      case x : org.postgresql.util.PGobject =>
        x.getType match {
          case "json" => Json.parse(x.getValue)
          case _ => JsNull
        }
      case x => x
    }
  }

  def execute(sql: String) {
    val conn = ds.borrowConnection()
    val stmt = conn.createStatement(java.sql.ResultSet.TYPE_FORWARD_ONLY,
      java.sql.ResultSet.CONCUR_UPDATABLE)
    try {
      """;\s+""".r.split(sql.trim()).filterNot(_.isEmpty).foreach{q => 
        log.info(q)
        conn.prepareStatement(q.trim()).executeUpdate
      }
    } catch {
      // SQL cmd exception
      case exception : Throwable =>
      // log.error(exception.toString)
      log.error(exception.getMessage())
      throw exception
    } finally {
      conn.close()
    }
  }

  /* Issues a query */
  def issueQuery(sql: String)(op: (java.sql.ResultSet) => Unit) = {
    val conn = ds.borrowConnection()
    try {
      log.info(sql)
      conn.setAutoCommit(false);
      val stmt = conn.createStatement(java.sql.ResultSet.TYPE_FORWARD_ONLY,
        java.sql.ResultSet.CONCUR_READ_ONLY);
      stmt.setFetchSize(5000);
      val rs = stmt.executeQuery(sql)
      while(rs.next()){
        op(rs)
      }
    } catch {
      // SQL cmd exception
      case exception : Throwable =>
      log.error(exception.toString)
      throw exception
    } finally {
      conn.close() 
    }
  }


  def selectAsMap(sql: String) : List[Map[String, Any]] = {
    val conn = ds.borrowConnection()
    conn.setAutoCommit(false)
    try {
      val stmt = conn.createStatement(
        java.sql.ResultSet.TYPE_FORWARD_ONLY, java.sql.ResultSet.CONCUR_READ_ONLY)
      stmt.setFetchSize(10000)
      val rs = stmt.executeQuery(sql)
      // No result return
      if (!rs.isBeforeFirst) {
        log.warning(s"query returned no results: ${sql}")
        Iterator.empty.toSeq
      } else {
        val resultIter = new Iterator[Map[String, Any]] {
          def hasNext = {
            // TODO: This is expensive
            !(rs.isLast)
          }              
          def next() = {
            rs.next()
            val metadata = rs.getMetaData()
            (1 to metadata.getColumnCount()).map { i => 
              val label = metadata.getColumnLabel(i)
              val data = unwrapSQLType(rs.getObject(i))
              (label, data)
            }.filter(_._2 != null).toMap
          }
        }
        resultIter.toSeq
      }
    } catch {
      // SQL cmd exception
      case exception : Throwable =>
        log.error(exception.toString)
        throw exception
    } finally {
      conn.close()
    }


    ds.DB.readOnly { implicit session =>
      SQL(sql).map(_.toMap).list.apply()
    }
  }

  def keyType = "bigserial"
  def stringType = "text"
  def randomFunc = "RANDOM()"


  def checkGreenplumSQL = s"""
    SELECT version() LIKE '%Greenplum%';
  """

  def createWeightsSQL = s"""
    DROP TABLE IF EXISTS ${WeightsTable} CASCADE;
    CREATE TABLE ${WeightsTable}(
      id bigserial primary key,
      initial_value real,
      is_fixed boolean,
      description text,
      cardinality_values text,
      count bigint);
    ALTER SEQUENCE ${WeightsTable}_id_seq MINVALUE -1 RESTART WITH 0;
  """

  def createWeightsTempSQL = s"""
    DROP TABLE IF EXISTS ${WeightsTableTemp} CASCADE;
    CREATE TABLE ${WeightsTableTemp}(
      initial_value double precision,
      is_fixed boolean,
      description text);
  """

  def copyLastWeightsSQL = s"""
    DROP TABLE IF EXISTS ${lastWeightsTable} CASCADE;
    SELECT X.*, Y.weight INTO ${lastWeightsTable}
      FROM ${WeightsTable} AS X INNER JOIN ${WeightResultTable} AS Y ON X.id = Y.id
      ORDER BY id ASC;
  """

  def createVariablesHoldoutSQL = s"""
    DROP TABLE IF EXISTS ${VariablesHoldoutTable} CASCADE; 
    CREATE TABLE ${VariablesHoldoutTable}(
      variable_id bigint primary key);
  """

  def createSequencesSQL = s"""
    DROP SEQUENCE IF EXISTS ${IdSequence} CASCADE;
    CREATE SEQUENCE ${IdSequence} MINVALUE -1 START 0;
  """

  def createInferenceResultSQL = s"""
    DROP TABLE IF EXISTS ${VariableResultTable} CASCADE; 
    CREATE TABLE ${VariableResultTable}(
      id bigint, 
      category bigint, 
      expectation double precision);
  """

  def createInferenceResultWeightsSQL = s"""
    DROP TABLE IF EXISTS ${WeightResultTable} CASCADE; 
    CREATE TABLE ${WeightResultTable}(
      id bigint primary key, 
      weight double precision);
  """

  def selectWeightsForDumpSQL = s"""
    SELECT id AS "id", is_fixed AS "is_fixed", initial_value AS "initial_value", description AS "description"
    FROM ${WeightsTable} ORDER BY description;
  """

  def createInferenceResultIndiciesSQL = s"""
    DROP INDEX IF EXISTS ${WeightResultTable}_idx CASCADE;
    DROP INDEX IF EXISTS ${VariableResultTable}_idx CASCADE;
    CREATE INDEX ${WeightResultTable}_idx ON ${WeightResultTable} (weight);
    CREATE INDEX ${VariableResultTable}_idx ON ${VariableResultTable} (expectation);
  """

  def createInferenceViewSQL(relationName: String, columnName: String) = s"""
    CREATE VIEW ${relationName}_${columnName}_inference AS
    (SELECT ${relationName}.*, mir.category, mir.expectation FROM
    ${relationName}, ${VariableResultTable} mir
    WHERE ${relationName}.id = mir.id
    ORDER BY mir.expectation DESC);
  """

  def createBucketedCalibrationViewSQL(name: String, inferenceViewName: String, buckets: List[Bucket]) = {
    val bucketCaseStatement = buckets.zipWithIndex.map { case(bucket, index) =>
      s"WHEN expectation >= ${bucket.from} AND expectation <= ${bucket.to} THEN ${index}"
    }.mkString("\n")
    s"""CREATE VIEW ${name} AS
      SELECT ${inferenceViewName}.*, CASE ${bucketCaseStatement} END bucket
      FROM ${inferenceViewName} ORDER BY bucket ASC;"""
  }

  def createCalibrationViewBooleanSQL(name: String, bucketedView: String, columnName: String) =  s"""
    CREATE VIEW ${name} AS
    SELECT b1.bucket, b1.num_variables, b2.num_correct, b3.num_incorrect FROM
    (SELECT bucket, COUNT(*) AS num_variables from ${bucketedView} GROUP BY bucket) b1
    LEFT JOIN (SELECT bucket, COUNT(*) AS num_correct from ${bucketedView} 
      WHERE ${columnName}=true GROUP BY bucket) b2 ON b1.bucket = b2.bucket
    LEFT JOIN (SELECT bucket, COUNT(*) AS num_incorrect from ${bucketedView} 
      WHERE ${columnName}=false GROUP BY bucket) b3 ON b1.bucket = b3.bucket 
    ORDER BY b1.bucket ASC;
  """

  def createCalibrationViewMultinomialSQL(name: String, bucketedView: String, columnName: String) =  s"""
    CREATE VIEW ${name} AS
    SELECT b1.bucket, b1.num_variables, b2.num_correct, b3.num_incorrect FROM
    (SELECT bucket, COUNT(*) AS num_variables from ${bucketedView} GROUP BY bucket) b1
    LEFT JOIN (SELECT bucket, COUNT(*) AS num_correct from ${bucketedView} 
      WHERE ${columnName} = category GROUP BY bucket) b2 ON b1.bucket = b2.bucket
    LEFT JOIN (SELECT bucket, COUNT(*) AS num_incorrect from ${bucketedView} 
      WHERE ${columnName} != category GROUP BY bucket) b3 ON b1.bucket = b3.bucket 
    ORDER BY b1.bucket ASC;
  """

  def selectCalibrationDataSQL(name: String) = s"""
    SELECT bucket as "bucket", num_variables AS "num_variables", 
      num_correct AS "num_correct", num_incorrect AS "num_incorrect"
    FROM ${name};
  """

  def createMappedWeightsViewSQL = s"""
    CREATE VIEW ${VariableResultTable}_mapped_weights AS
    SELECT ${WeightsTable}.*, ${WeightResultTable}.weight FROM
    ${WeightsTable} JOIN ${WeightResultTable} ON ${WeightsTable}.id = ${WeightResultTable}.id
    ORDER BY abs(weight) DESC;
  """

  def createAssignIdFunctionSQL = 
    """
    DROP LANGUAGE IF EXISTS plpgsql CASCADE;
    DROP LANGUAGE IF EXISTS plpythonu CASCADE;
    CREATE LANGUAGE plpgsql;
    CREATE LANGUAGE plpythonu;

    CREATE OR REPLACE FUNCTION clear_count_1(sid int) RETURNS int AS 
    \$\$
    if '__count_1' in SD:
      SD['__count_1'] = -1
      return 1
    return 0
    \$\$ LANGUAGE plpythonu;
     
     
    CREATE OR REPLACE FUNCTION updateid(startid bigint, sid int, sids int[], base_ids bigint[], base_ids_noagg bigint[]) RETURNS bigint AS 
    \$\$
    if '__count_1' in SD:
      a = SD['__count_2']
      b = SD['__count_1']
      SD['__count_2'] = SD['__count_2'] - 1
      if SD['__count_2'] < 0:
        SD.pop('__count_1')
      return startid+b-a
    else:
      for i in range(0, len(sids)):
        if sids[i] == sid:
          SD['__count_1'] = base_ids[i] - 1
          SD['__count_2'] = base_ids_noagg[i] - 1
      a = SD['__count_2']
      b = SD['__count_1']
      SD['__count_2'] = SD['__count_2'] - 1
      if SD['__count_2'] < 0:
        SD.pop('__count_1')
      return startid+b-a
      
    \$\$ LANGUAGE plpythonu;
     
    CREATE OR REPLACE FUNCTION fast_seqassign(tname character varying, startid bigint) RETURNS TEXT AS 
    \$\$
    BEGIN
      EXECUTE 'drop table if exists tmp_gpsid_count cascade;';
      EXECUTE 'drop table if exists tmp_gpsid_count_noagg cascade;';
      EXECUTE 'create table tmp_gpsid_count as select gp_segment_id as sid, count(clear_count_1(gp_segment_id)) as base_id from ' || quote_ident(tname) || ' group by gp_segment_id order by sid distributed by (sid);';
      EXECUTE 'create table tmp_gpsid_count_noagg as select * from tmp_gpsid_count distributed by (sid);';
      EXECUTE 'update tmp_gpsid_count as t set base_id = (SELECT SUM(base_id) FROM tmp_gpsid_count as t2 WHERE t2.sid <= t.sid);';
      RAISE NOTICE 'EXECUTING _fast_seqassign()...';
      EXECUTE 'select * from _fast_seqassign(''' || quote_ident(tname) || ''', ' || startid || ');';
      RETURN '';
    END;
    \$\$ LANGUAGE 'plpgsql';
     
    CREATE OR REPLACE FUNCTION _fast_seqassign(tname character varying, startid bigint)
    RETURNS TEXT AS
    \$\$
    DECLARE
      sids int[] :=  ARRAY(SELECT sid FROM tmp_gpsid_count ORDER BY sid);
      base_ids bigint[] :=  ARRAY(SELECT base_id FROM tmp_gpsid_count ORDER BY sid);
      base_ids_noagg bigint[] :=  ARRAY(SELECT base_id FROM tmp_gpsid_count_noagg ORDER BY sid);
      tsids text;
      tbase_ids text;
      tbase_ids_noagg text;
    BEGIN
      SELECT INTO tsids array_to_string(sids, ',');
      SELECT INTO tbase_ids array_to_string(base_ids, ',');
      SELECT INTO tbase_ids_noagg array_to_string(base_ids_noagg, ',');
      EXECUTE 'update ' || tname || ' set id = updateid(' || startid || ', gp_segment_id, ARRAY[' || tsids || '], ARRAY[' || tbase_ids || '], ARRAY[' || tbase_ids_noagg || ']);';
      RETURN '';
    END;
    \$\$
    LANGUAGE 'plpgsql';
    """


  def executeCmd(cmd: String) : Unit = {
    // Make the file executable, if necessary
    val file = new java.io.File(cmd)
    if (file.isFile) file.setExecutable(true, false)
    log.debug(s"""Executing: "$cmd" """)
    val processLogger = ProcessLogger(line => log.info(line))
    cmd!(processLogger) match {
      case 0 => 
      case _ => 
        throw new RuntimeException(s"Script failed")
    }
  }


  def init() : Unit = {
  }


  def dumpFactorGraph(serializer: Serializer, schema: Map[String, _ <: VariableDataType],
    factorDescs: Seq[FactorDesc], holdoutFraction: Double, holdoutQuery: Option[String],
    weightsPath: String, variablesPath: String, factorsPath: String, edgesPath: String) : Unit = {

    var numVariables  : Long = 0
    var numFactors    : Long = 0
    var numWeights    : Long = 0
    var numEdges      : Long = 0

    val randomGen = new Random()
    val weightMap = scala.collection.mutable.Map[String, Long]()
    val variableTypeMap = scala.collection.mutable.Map[String, String]()

    val customHoldout = holdoutQuery match {
      case Some(query) => true
      case None => false
    }
    
    log.info(s"Dumping factor graph...")
    log.info("Dumping variables...")

    schema.foreach { case(variable, dataType) =>
      val Array(relation, column) = variable.split('.')

      val cardinality = dataType match {
        case BooleanType => 1
        case MultinomialType(x, y) => x.toLong
      }


      val customCardinality = cardinality match {
        case -1 => "cardinality"
        case _ => cardinality.toString()
      }
      
      val selectVariablesForDumpSQL = s"""
        SELECT ${relation}.id, (${variable} IS NOT NULL), ${variable}::int, (${VariablesHoldoutTable}.variable_id IS NOT NULL), ${customCardinality}, isfixed
        FROM ${relation} LEFT JOIN ${VariablesHoldoutTable}
        ON ${relation}.id = ${VariablesHoldoutTable}.variable_id"""

      dataType match {
        case BooleanType => variableTypeMap(s"${variable}") = "Boolean"
        case MultinomialType(x, y) => {
          if (x == -1) variableTypeMap(s"${variable}") = "Custom"
          else variableTypeMap(s"${variable}") = "Multinomial"
        }
      }

      issueQuery(selectVariablesForDumpSQL) { rs =>
        var isEvidence = rs.getBoolean(2)
        var holdout = rs.getBoolean(4)

        // assign holdout
        if (customHoldout && isEvidence && holdout) {
          isEvidence = false
        } else if (!customHoldout && isEvidence && randomGen.nextFloat < holdoutFraction) {
          isEvidence = false
        }

        // use edge count to do something...
        val upperBound : Long = dataType match {
          case BooleanType => 1
          case MultinomialType(x, y) => y.toLong
        }
        // is fixed? (observation variables)
        var edgeCount : Long = rs.getBoolean(6) match {
          case true => 1
          case _ => 0
        }
        edgeCount += (upperBound << 1)

        serializer.addVariable(
          rs.getLong(1),            // id
          isEvidence,               // is evidence
          rs.getLong(3),            // initial value
          dataType.toString,        // data type            
          edgeCount,                // edge count
          rs.getLong(5))            // cardinality
        numVariables += 1
      }

    }

    log.info("Dumping weights...")
    issueQuery(selectWeightsForDumpSQL) { rs => 
      val id = rs.getLong(1)
      serializer.addWeight(id, rs.getBoolean(2), rs.getDouble(3))
      numWeights += 1
      weightMap(rs.getString(4)) = id
    }


    log.info("Dumping factors...")

    // weightmap file
    val factorGraphDumpFileWeightMap = new File(s"${Context.outputDir}/graph.weightmap")
    val weightmapWriter = new PrintWriter(factorGraphDumpFileWeightMap)

    factorDescs.foreach { factorDesc =>
      val functionName = factorDesc.func.getClass.getSimpleName

      val isCustomSupport = factorDesc.func.getClass.getSimpleName == "MultinomialFactorFunctionWithSupport"
      // let the smallest string be the starting weight description
      // support table contains a column "val". It is the cardinality values seperated by ",". 
      // Custom support set does not work with predicates, but if the predicate variable is a
      // custom cardinality variable, it is ok.
      var startCardinalityStr = ""
      if (isCustomSupport) {
        issueQuery(s"SELECT MIN(val) FROM ${factorDesc.func.supportTable}") { rs =>
          startCardinalityStr = rs.getString(1)
        }
      }

      log.info(s"Dumping inference ${factorDesc.weightPrefix}...")

      val selectInputQueryForDumpSQL = s"""
        SELECT ${factorDesc.name}_query_user.*
        FROM ${factorDesc.name}_query_user
        """

      val variables = factorDesc.func.variables

      // create cardinality description
      def getCardinalityStr(equalPredicate: Option[Long], variable: String) : String = {
        val predicateValue : Long = equalPredicate match {
          case Some(s) => s
          case None => -1
        }
        val groundValue : Long = variableTypeMap(variable) match {
          case "Boolean" => 1
          case _ => 0
        }
        
        if (variableTypeMap(variable) == "Custom" && functionName == "MultinomialFactorFunction")
          null
        else if (predicateValue > 0)
          "%05d".format(predicateValue)
        else
          "%05d".format(groundValue)
      }

      var cardinalityStr = functionName match {
        case "TreeConstraintFactorFunction" | "ParentLabelConstraintFactorFunction" => ""
        case "MultinomialFactorFunctionWithSupport" => startCardinalityStr
        case _ => variables.map(v => getCardinalityStr(v.predicate, s"${v.key}")).filter(_ != null).mkString(",")
      }

      // dump weight mapping table
      if (isCustomSupport) {

        // upper bounds for variable in the support set
        // e.g. for variables a, b, c, with upper bound u1, u2, u3
        // we use a*u2*u3 + b*u3 + c as key for weightmap
        val boundList = factorDesc.func.variables.filter(v => variableTypeMap(v.key) == "Multinomial").map { v =>
            schema(v.key) match {
              case MultinomialType(x, y) => y.toLong
            }
        }
        val numVar = boundList.length
        weightmapWriter.println(s"${numVar} ${boundList.mkString(" ")}")

        // count
        var count = 0L
        issueQuery(s"SELECT COUNT(*) FROM ${factorDesc.func.supportTable}") { rs =>
          count = rs.getLong(1)
        }
        weightmapWriter.println(count)

        val selectSupportForDumpSQL = s"""
          SELECT val, row_number() OVER()-1 AS id 
          FROM (SELECT val FROM ${factorDesc.func.supportTable} ORDER BY val) tmp"""
        issueQuery(selectSupportForDumpSQL) { rs =>
          // calculate key
          val cardinalities = rs.getString(1).split(",").map(_.toLong)
          var key : Long = 0
          for (i <- 0 to numVar - 1) {
            key = key * boundList(i) + cardinalities(i)
          }
          // log.info("===================")
          // log.info(rs.getString(1))
          // log.info(s"key = ${key} val = ${rs.getString(2)}")
          weightmapWriter.println(s"${key} ${rs.getString(2)}")
          // weightmapWriter.println(s"${rs.getString(1)} ${rs.getString(2)}")
        }
        // insert weight map for other possible worlds
        weightmapWriter.println(s"-1 ${count}")

      }


      issueQuery(selectInputQueryForDumpSQL) { rs =>

        // generate weight description
        val weightCmd = factorDesc.weightPrefix + "-" + factorDesc.weight.variables.map(v => 
          rs.getString(v)).mkString("") + "-" + cardinalityStr
        // log.info(weightCmd)

        var numFactorEdges = 0
        var pos = 0
        variables.foreach { case(v) =>
          if (v.isArray) {
            val ids = rs.getArray(s"${v.relation}.ids").toString.split(",|\\}|\\{")
            val predicates = v.predicateFromData match {
              case Some(s) => rs.getArray(s).toString.split(",|\\}|\\{")
              case None => variableTypeMap(v.key) match {
                case "Boolean" => Array.fill(ids.length)("1")
                case "Multinomial" => Array.fill(ids.length)("-2")
                case "Custom" => Array.fill(ids.length)("-1")
              }
            }
            for (i <- 0 to ids.length-1) {
              val id = ids(i)
              if (id != "") {
                serializer.addEdge(id.toLong, numFactors, pos, !v.isNegated, 
                  predicates.asInstanceOf[Array[String]](i).toLong)
                numEdges += 1
                pos += 1
                numFactorEdges += 1
              }
            }
          } else {
            val predicate = v.predicateFromData match {
              case Some(s) => rs.getLong(s)
              case None => v.predicate match {
                case Some(p) => p
                case None => variableTypeMap(v.key) match {
                  case "Boolean" => 1
                  case "Multinomial" => -2
                  case "Custom" => -1
                }
              }
            }
            serializer.addEdge(rs.getLong(s"${v.relation}.id"),
              numFactors, pos, !v.isNegated, predicate)
            numEdges += 1
            pos += 1
            numFactorEdges += 1
          }
        }
        serializer.addFactor(numFactors, weightMap(weightCmd), functionName, numFactorEdges)

        numFactors += 1
      }

    }

    serializer.writeMetadata(numWeights, numVariables, numFactors, numEdges,
      weightsPath, variablesPath, factorsPath, edgesPath)

    serializer.close()
    weightmapWriter.close()


  }

  def generatePsqlCmdString(dbSettings: DbSettings, sql: String) : String = {
    // Get Database-related settings
    val dbname = dbSettings.dbname
    val pguser = dbSettings.user
    val pgport = dbSettings.port
    val pghost = dbSettings.host
    // TODO do not use password for now
    val dbnameStr = dbname match {
      case null => ""
      case _ => s" -d ${dbname} "
    }
    val pguserStr = pguser match {
      case null => ""
      case _ => s" -U ${pguser} "
    }
    val pgportStr = pgport match {
      case null => ""
      case _ => s" -p ${pgport} "
    }
    val pghostStr = pghost match {
      case null => ""
      case _ => s" -h ${pghost} "
    }

    "psql " + dbnameStr + pguserStr + pgportStr + pghostStr + " -c " + "\"\"\"" + sql + "\"\"\""
  }

  def groundFactorGraph(schema: Map[String, _ <: VariableDataType], factorDescs: Seq[FactorDesc],
    holdoutFraction: Double, holdoutQuery: Option[String], skipLearning: Boolean, weightTable: String, dbSettings: DbSettings) {

    // variable data type map
    val variableTypeMap = scala.collection.mutable.Map[String, String]();

    // We write the grounding queries to this SQL file
    val sqlFile = File.createTempFile(s"grounding", ".sql")
    val writer = new PrintWriter(sqlFile)
    val assignIdFile = File.createTempFile(s"assignId", ".sh")
    val assignidWriter = new PrintWriter(assignIdFile)
    log.info(s"""Writing grounding queries to file="${sqlFile}" """)

    // If skip_learning and use the last weight table, copy it before removing it
    if (skipLearning && weightTable.isEmpty()) {
      writer.println(copyLastWeightsSQL)
    }

    writer.println(createWeightsSQL)
    writer.println(createVariablesHoldoutSQL)

    // check whether Greenplum is used
    var usingGreenplum = false
    issueQuery(checkGreenplumSQL) { rs => 
      usingGreenplum = rs.getBoolean(1) 
    }

    log.info("Using Greenplum = " + usingGreenplum.toString)

    // assign id
    if (usingGreenplum) {
      val createAssignIdPrefix = generatePsqlCmdString(dbSettings, createAssignIdFunctionSQL)
      assignidWriter.println(createAssignIdPrefix)
    } else {
      writer.println(createSequencesSQL)
    }
    var idoffset : Long = 0

    // Ground all variables in the schema
    schema.foreach { case(variable, dataType) =>
      val Array(relation, column) = variable.split('.')

      if (usingGreenplum) {
        val assignIdSQL = generatePsqlCmdString(dbSettings, 
          s""" SELECT fast_seqassign('${relation}', ${idoffset});""")
        assignidWriter.println(assignIdSQL)
        val getOffset = s"SELECT count(*) FROM ${relation};"
        issueQuery(getOffset) { rs =>
          idoffset = idoffset + rs.getLong(1);
        }
      } else {
        writer.println(s"""
          UPDATE ${relation} SET id = nextval('${IdSequence}');
          """)
      }

      // data type map
      dataType match {
        case BooleanType => variableTypeMap(variable) = "Boolean"
        case MultinomialType(x, y) => {
          if (x == -1) variableTypeMap(variable) = "Custom"
          else variableTypeMap(variable) = "Multinomial"
        }
      }

      // Create a cardinality table for the variable
      // note we use five-digit fixed-length representation here
      val cardinalityValues = dataType match {
        case BooleanType => "('00001')"
        case MultinomialType(x, y) => {
          if (x == -1) "('00000')"
          else (0 to y-1).map(n => s"""('${"%05d".format(n)}')""").mkString(", ")
        }
      }
      val cardinalityTableName = s"${relation}_${column}_cardinality"
        writer.println(s"""
          DROP TABLE IF EXISTS ${cardinalityTableName} CASCADE;
          CREATE TABLE ${cardinalityTableName}(cardinality text);
          INSERT INTO ${cardinalityTableName} VALUES ${cardinalityValues};
          """)
    }

    // Assign the holdout - Random (default) or user-defined query
    holdoutQuery match {
      case Some(userQuery) => writer.println(userQuery + ";")
      case None =>
    }

    // Create view for each inference rule
    factorDescs.foreach { factorDesc =>
      
      // input query
      writer.println(s"""
        DROP VIEW IF EXISTS ${factorDesc.name}_query_user CASCADE;
        CREATE VIEW ${factorDesc.name}_query_user AS ${factorDesc.inputQuery};
        """)

      // boolean is custom support set for multinomial
      val isCustomSupport = factorDesc.func.getClass.getSimpleName == "MultinomialFactorFunctionWithSupport"

      // create cardinality table for each predicate
      factorDesc.func.variables.zipWithIndex.foreach { case(v,idx) => {
        val cardinalityTableName = s"${factorDesc.weightPrefix}_cardinality_${idx}"
        writer.println(s"""DROP TABLE IF EXISTS ${cardinalityTableName} CASCADE;""")
        if (v.isArray) {
          writer.println(s"""
            CREATE TABLE ${cardinalityTableName}(cardinality text);
            INSERT INTO ${cardinalityTableName} VALUES ('00000');
            """)
        } else {
          v.predicate match {
            case Some(x) => writer.println(s"""
              CREATE TABLE ${cardinalityTableName}(cardinality text);
              INSERT INTO ${cardinalityTableName} VALUES ('${"%05d".format(x)}');
              """)
            case None => writer.println(s"""
              SELECT * INTO ${cardinalityTableName} FROM ${v.headRelation}_${v.field}_cardinality;
              """)
          }
        }
      }
      }

      // def generateWeightCmd(weightPrefix: String, weightVariables: Seq[String], cardinalityValues: Seq[String]) : String = 
      // weightVariables.map ( v => s"""(CASE WHEN "${v}" IS NULL THEN '' ELSE "${v}"::text END)""" )
      //   .mkString(" || ") match {
      //   case "" => s"""'${weightPrefix}-' || '-' || ${cardinalityValues.filter(_ != null).mkString(" || ',' || ")} """
      //   case x => s"""'${weightPrefix}-' || ${x} || '-' || ${cardinalityValues.filter(_ != null).mkString(" || ',' || ")}"""
      // }

      def generateWeightCmd(weightPrefix: String, weightVariables: Seq[String]) : String = 
      weightVariables.map ( v => s"""(CASE WHEN "${v}" IS NULL THEN '' ELSE "${v}"::text END)""" )
        .mkString(" || ") match {
        case "" => s"""'${weightPrefix}-' """
        case x => s"""'${weightPrefix}-' || ${x}"""
      }

      // Ground weights for each inference rule
      val weightValue = factorDesc.weight match { 
        case x : KnownFactorWeight => x.value
        case _ => 0.0
      }

      val isFixed = factorDesc.weight.isInstanceOf[KnownFactorWeight]
      val cardinalityValues = factorDesc.func.variables.zipWithIndex.map { case(v,idx) => 
        if (v.isArray || (variableTypeMap(v.key) == "Custom" && factorDesc.func.getClass.getSimpleName == "MultinomialFactorFunction")) {
          null
        } else {
           s"""_c${idx}.cardinality"""
        }
      }

      // // for the tree constraint factor function
      // if (factorDesc.func.getClass.getSimpleName == "TreeConstraintFactorFunction" ||
      //   factorDesc.func.getClass.getSimpleName == "parentLabelConstraintFactorFunction") {
      //   cardinalityValues = Seq[String]("''")
      // } else if (isCustomSupport) {
      //   // cardinality values are from a support table 
      //   cardinalityValues = Seq[String](s"s.val")
      // }
      
      var cardinalityTables = factorDesc.func.variables.zipWithIndex.map { case(v,idx) =>
          s"${factorDesc.weightPrefix}_cardinality_${idx} AS _c${idx}"
      }

      // custom support table
      if (isCustomSupport) {
        cardinalityTables = Seq[String](s"(SELECT val FROM ${factorDesc.func.supportTable} UNION SELECT 'other') s")
      }

      val weightCmd = generateWeightCmd(factorDesc.weightPrefix, factorDesc.weight.variables)

      // cardinality cmd for weight table
      var cardinalityCmd = s"""${cardinalityValues.filter(_ != null).mkString(" || ',' || ")}"""

      // note treeconstraint and parentLabelConstraint only has one weight, no cardinality related weights
      if (factorDesc.func.getClass.getSimpleName == "TreeConstraintFactorFunction" ||
        factorDesc.func.getClass.getSimpleName == "parentLabelConstraintFactorFunction") {
        cardinalityCmd = "''"
      } else if (isCustomSupport) {
        cardinalityCmd = "s.val"
      }

      // create a temporary weight table
      writer.println(createWeightsTempSQL)

      writer.println(s"""
        INSERT INTO ${WeightsTableTemp}(initial_value, is_fixed, description)
        SELECT DISTINCT ${weightValue} AS wValue, ${isFixed} AS wIsFixed, ${weightCmd} AS wCmd
        FROM ${factorDesc.name}_query_user;
        """)

      writer.println(s"""
        INSERT INTO ${WeightsTable}(initial_value, is_fixed, description, cardinality_values)
        SELECT DISTINCT w.initial_value, w.is_fixed, (w.description || '-' || ${cardinalityCmd}) as wCmd, ${cardinalityCmd}
        FROM ${WeightsTableTemp} AS w, ${cardinalityTables.mkString(", ")}
        ORDER BY wCmd;
        """)

      factorDesc.func.variables.zipWithIndex.map { case(v,idx) =>
        writer.println(s"""DROP TABLE ${factorDesc.weightPrefix}_cardinality_${idx};""")
      }
    }

    // parser: set weights to -infinity for rules that are not in training set
    writer.println(s"""
      UPDATE ${WeightsTable}
      SET initial_value = ${sys.env("WEIGHT_RULE_CONSTRAINT")}, is_fixed = true
      WHERE description LIKE 'factor_crfcfg%' and cardinality_values NOT IN (SELECT rule FROM rules);
      """)

    // skip learning: choose a table to copy weights from
    if (skipLearning) {
      val fromWeightTable = weightTable.isEmpty() match {
        case true => lastWeightsTable
        case false => weightTable
      }
      log.info(s"""Using weights in TABLE ${fromWeightTable} by matching description""")

      // Already set -l 0 for sampler
      writer.println(s"""
        UPDATE ${WeightsTable} SET initial_value = weight 
        FROM ${fromWeightTable} 
        WHERE dd_graph_weights.description = ${fromWeightTable}.description;
        """)
    }

    writer.println(s"""CREATE INDEX ${WeightsTable}_desc_idx ON ${WeightsTable}(description);""")
    writer.println(s"""ANALYZE ${WeightsTable};""")

    writer.close()
    assignidWriter.close()

    log.info("Executing grounding query...")
    executeCmd(assignIdFile.getAbsolutePath())
    execute(Source.fromFile(sqlFile).getLines.mkString)
  }

  def bulkCopyWeights(weightsFile: String) : Unit
  def bulkCopyVariables(variablesFile: String) : Unit

  def writebackInferenceResult(variableSchema: Map[String, _ <: VariableDataType],
    variableOutputFile: String, weightsOutputFile: String) = {

    execute(createInferenceResultSQL)
    execute(createInferenceResultWeightsSQL)
    // execute(createMappedInferenceResultView)

    log.info("Copying inference result weights...")
    bulkCopyWeights(weightsOutputFile)
    log.info("Copying inference result variables...")
    bulkCopyVariables(variableOutputFile)
    log.info("Creating indicies on the inference result...")
    execute(createInferenceResultIndiciesSQL)

    // Each (relation, column) tuple is a variable in the plate model.
     // Find all (relation, column) combinations
    val relationsColumns = variableSchema.keys map (_.split('.')) map {
      case Array(relation, column) => (relation, column)
    }

    execute(createMappedWeightsViewSQL)

    relationsColumns.foreach { case(relationName, columnName) =>
      execute(createInferenceViewSQL(relationName, columnName))
    //   // TODO
    //   execute(createVariableWeightsViewSQL(relationName, columnName))
    }
  }

  def getCalibrationData(variable: String, dataType: VariableDataType, 
    buckets: List[Bucket]) : Map[Bucket, BucketData] = {

    val Array(relationName, columnName) = variable.split('.')
    val inferenceViewName = s"${relationName}_${columnName}_inference"
    val bucketedViewName = s"${relationName}_${columnName}_inference_bucketed"
    val calibrationViewName = s"${relationName}_${columnName}_calibration"

    execute(createBucketedCalibrationViewSQL(bucketedViewName, inferenceViewName, buckets))
    log.info(s"created calibration_view=${calibrationViewName}")
    dataType match {
      case BooleanType => 
        execute(createCalibrationViewBooleanSQL(calibrationViewName, bucketedViewName, columnName))
      case MultinomialType(_, _) =>
        execute(createCalibrationViewMultinomialSQL(calibrationViewName, bucketedViewName, columnName))
    }
    
    val bucketData = selectAsMap(selectCalibrationDataSQL(calibrationViewName)).map { row =>
      val bucket = row("bucket")
      val data = BucketData(
        row.get("num_variables").map(_.asInstanceOf[Long]).getOrElse(0), 
        row.get("num_correct").map(_.asInstanceOf[Long]).getOrElse(0), 
        row.get("num_incorrect").map(_.asInstanceOf[Long]).getOrElse(0))
      (bucket, data)
    }.toMap
    buckets.zipWithIndex.map { case (bucket, index) =>
      (bucket, bucketData.get(index).getOrElse(BucketData(0,0,0)))
    }.toMap
  }




}
