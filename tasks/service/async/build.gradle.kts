/*
 * Copyright (C) 2022 Dremio
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

plugins { id("nessie-conventions-server") }

publishingHelper { mavenName = "Nessie - Tasks - Async" }

dependencies {
  compileOnly(libs.vertx.core)
  compileOnly(libs.microprofile.contextpropagation.api)

  compileOnly(project(":nessie-immutables-std"))
  annotationProcessor(project(":nessie-immutables-std", configuration = "processor"))

  testImplementation(platform(libs.junit.bom))
  testImplementation(libs.bundles.junit.testing)

  testImplementation(libs.vertx.core)
  testImplementation(libs.microprofile.contextpropagation.api)
}
