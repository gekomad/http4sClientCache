ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "3.3.7"

lazy val root = (project in file("."))
  .settings(
    name := "http4sClientCache"
  )
val http4sVersion = "1.0.0-M44"
val circeVersion  = "0.14.15"

libraryDependencies += "org.http4s"                   %% "http4s-circe"           % http4sVersion
libraryDependencies += "org.http4s"                   %% "http4s-dsl"             % http4sVersion
libraryDependencies += "org.http4s"                   %% "http4s-jdk-http-client" % "1.0.0-M10"
libraryDependencies += "ch.qos.logback"                % "logback-classic"        % "1.5.32"
libraryDependencies += "io.circe"                     %% "circe-generic"          % circeVersion
libraryDependencies += "io.circe"                     %% "circe-parser"           % circeVersion
libraryDependencies += "com.github.cb372"             %% "cats-retry"             % "4.0.0"
libraryDependencies += "org.typelevel"                %% "log4cats-slf4j"         % "2.8.0"
libraryDependencies += "com.github.ben-manes.caffeine" % "caffeine"               % "3.2.4"
libraryDependencies += "org.typelevel"                %% "munit-cats-effect"      % "2.2.0" % Test
scalacOptions ++= Seq(
  "-Xmax-inlines",
  "300",
  "-language:postfixOps",
  "-deprecation",
  "-feature",
  "-unchecked",
  "-old-syntax",
  "-rewrite",
  "-Werror",
  "-Wvalue-discard",
  "-Wunused:all",
  "-Wnonunit-statement"
)
