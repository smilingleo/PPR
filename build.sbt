import com.typesafe.sbt.SbtMultiJvm
import com.typesafe.sbt.SbtMultiJvm.MultiJvmKeys.MultiJvm

val akkaVersion = "2.3.11"

val sprayVersion = "1.3.2"

val project = Project(
  id = "parallel-payment-run",
  base = file("."),
  settings = Project.defaultSettings ++ SbtMultiJvm.multiJvmSettings ++ Seq(
    name := "parallel-payment-run",
    version := "2.3.9",
    scalaVersion := "2.10.4",
    resolvers += "dnvriend at bintray" at "http://dl.bintray.com/dnvriend/maven",
	libraryDependencies += "com.github.dnvriend" %% "akka-persistence-jdbc" % "1.1.5",
    scalacOptions in Compile ++= Seq("-encoding", "UTF-8", "-target:jvm-1.6", "-deprecation", "-feature", "-unchecked", "-Xlog-reflective-calls", "-Xlint"),
    javacOptions in Compile ++= Seq("-source", "1.6", "-target", "1.6", "-Xlint:unchecked", "-Xlint:deprecation"),
    libraryDependencies ++= Seq(
      "io.spray"          %% "spray-can"     % sprayVersion withSources() withJavadoc(),
      "io.spray"          %% "spray-routing" % sprayVersion withSources() withJavadoc(),
      "io.spray"          %% "spray-json"    % "1.3.1",    
      "com.typesafe.akka" %% "akka-cluster"  % akkaVersion withSources() withJavadoc(),
      "com.typesafe.akka" %% "akka-contrib"  % akkaVersion withSources() withJavadoc(),
      "com.typesafe.akka" %% "akka-persistence-experimental" % akkaVersion withSources() withJavadoc(),
      "com.typesafe.akka" %% "akka-stream-experimental" % "1.0-RC2" withSources(),
      "com.typesafe.akka" %% "akka-stream-experimental" % "1.0-RC2" withSources(),
      "com.typesafe.akka" %% "akka-http-scala-experimental" % "1.0-RC2" withSources(),
      "com.typesafe.akka" %% "akka-multi-node-testkit" % akkaVersion withSources() withJavadoc(),
      "org.json4s" %% "json4s-native" % "3.2.11" withSources(),
      "org.json4s" %% "json4s-jackson" % "3.2.11" withSources(),
      "org.scalatest" %% "scalatest" % "2.0" % "test" withSources() withJavadoc(),
      "mysql"		   % "mysql-connector-java" % "5.1.35",
      "org.fusesource" % "sigar" % "1.6.4"),
    javaOptions in run ++= Seq(
      "-Djava.library.path=./sigar",
      "-Xms128m", "-Xmx1024m"),
    Keys.fork in run := true,  
    mainClass in (Compile, run) := Some("sample.cluster.simple.SimpleClusterApp"),
    // make sure that MultiJvm test are compiled by the default test compilation
    compile in MultiJvm <<= (compile in MultiJvm) triggeredBy (compile in Test),
    // disable parallel tests
    parallelExecution in Test := false,
    // make sure that MultiJvm tests are executed by the default test target, 
    // and combine the results from ordinary test and multi-jvm tests
    executeTests in Test <<= (executeTests in Test, executeTests in MultiJvm) map {
      case (testResults, multiNodeResults)  =>
        val overall =
          if (testResults.overall.id < multiNodeResults.overall.id)
            multiNodeResults.overall
          else
            testResults.overall
        Tests.Output(overall,
          testResults.events ++ multiNodeResults.events,
          testResults.summaries ++ multiNodeResults.summaries)
    }
  )
) configs (MultiJvm)
