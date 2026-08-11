// sbt-scalafmt stays on the 2.5 line: 2.6.x requires sbt 1.12.9+, and
// project/build.properties pins sbt 1.9.x
addSbtPlugin("com.github.sbt"   % "sbt-native-packager"       % "1.11.7")
addSbtPlugin("com.eed3si9n"     % "sbt-assembly"              % "2.4.1")
addSbtPlugin("com.timushev.sbt" % "sbt-updates"               % "0.7.0")
addSbtPlugin("com.github.cb372" % "sbt-explicit-dependencies" % "0.3.1")
addSbtPlugin("org.scalameta"    % "sbt-scalafmt"              % "2.5.6")
