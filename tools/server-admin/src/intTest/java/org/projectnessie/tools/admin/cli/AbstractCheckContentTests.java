/*
 * Copyright (C) 2024 Dremio
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
package org.projectnessie.tools.admin.cli;

import static java.lang.String.format;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.projectnessie.tools.admin.cli.BaseCommand.EXIT_CODE_CONTENT_ERROR;
import static org.projectnessie.versioned.store.DefaultStoreWorker.payloadForContent;
import static org.projectnessie.versioned.testworker.OnRefOnly.onRef;

import io.quarkus.test.junit.main.QuarkusMainLauncher;
import io.quarkus.test.junit.main.QuarkusMainTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.projectnessie.model.Content;
import org.projectnessie.model.ContentKey;
import org.projectnessie.model.IcebergTable;
import org.projectnessie.nessie.relocated.protobuf.ByteString;
import org.projectnessie.versioned.Hash;
import org.projectnessie.versioned.testworker.OnRefOnly;

@QuarkusMainTest
abstract class AbstractCheckContentTests {

  private static final UUID CID_1 = UUID.randomUUID();
  private static final UUID CID_2 = UUID.randomUUID();
  private static final UUID CID_3 = UUID.randomUUID();
  private static final UUID CID_4 = UUID.randomUUID();

  private static final IcebergTable table1 =
      IcebergTable.of("meta_111", 1, 2, 3, 4, CID_1.toString());
  private static final IcebergTable table2 =
      IcebergTable.of("meta_222", 2, 3, 4, 5, CID_2.toString());
  private static final IcebergTable table3 =
      IcebergTable.of("meta_333", 3, 4, 5, 6, CID_3.toString());
  private static final IcebergTable table4 =
      IcebergTable.of("meta_444", 4, 5, 6, 7, CID_4.toString());

  private final BaseContentTest<CheckContentEntry> outer;

  AbstractCheckContentTests(BaseContentTest<CheckContentEntry> outer) {
    this.outer = outer;
  }

  @Test
  public void testEmptyRepo(QuarkusMainLauncher launcher) {
    outer.launchNoFile(launcher, "check-content");
    assertThat(outer.result.exitCode()).isEqualTo(0);
  }

  @Test
  public void testNonExistingKey(QuarkusMainLauncher launcher) throws Exception {
    outer.launch(launcher, "check-content", "-k", "namespace123", "-k", "unknown12345");
    assertThat(outer.entries)
        .hasSize(1)
        .first()
        .extracting(
            CheckContentEntry::getKey,
            CheckContentEntry::getStatus,
            CheckContentEntry::getContent,
            CheckContentEntry::getErrorMessage)
        .containsExactly(
            ContentKey.of("namespace123", "unknown12345"), "ERROR", null, "Missing content");
    assertThat(outer.result.exitCode()).isEqualTo(EXIT_CODE_CONTENT_ERROR);
  }

  @Test
  public void testParseError(QuarkusMainLauncher launcher) throws Exception {
    ContentKey k1 = ContentKey.of("table123");
    outer.commit(
        k1,
        UUID.randomUUID(),
        (byte) payloadForContent(Content.Type.ICEBERG_TABLE),
        ByteString.copyFrom(new byte[] {1, 2, 3}),
        false,
        true);

    outer.launch(launcher, "check-content");
    assertThat(outer.entries)
        .hasSize(1)
        .first()
        .extracting(
            CheckContentEntry::getKey,
            CheckContentEntry::getStatus,
            CheckContentEntry::getContent,
            CheckContentEntry::getErrorMessage,
            e ->
                e.getExceptionStackTrace() != null
                    && e.getExceptionStackTrace()
                        .contains("Protocol message contained an invalid tag"))
        .containsExactly(ContentKey.of("table123"), "ERROR", null, "Failure parsing data", true);
    assertThat(outer.result.exitCode()).isEqualTo(EXIT_CODE_CONTENT_ERROR);
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 3, 4, 5, 10})
  public void testWorkerError(int batchSize, QuarkusMainLauncher launcher) throws Exception {

    ByteString broken = ByteString.copyFrom(new byte[] {1, 2, 3});
    outer.commit(table1, broken);
    outer.commit(table2, broken);
    outer.commit(table3, broken);
    outer.commit(table4, broken);

    // Note: SimpleStoreWorker will not be able to parse IcebergTable objects
    outer.launch(launcher, "check-content", "--summary", "--batch=" + batchSize);
    assertThat(outer.entries.stream())
        .extracting(
            CheckContentEntry::getKey,
            CheckContentEntry::getStatus,
            e -> e.getErrorMessage() != null && !e.getErrorMessage().isEmpty(),
            e -> e.getExceptionStackTrace() != null && !e.getExceptionStackTrace().isEmpty())
        .containsExactlyInAnyOrder(
            tuple(
                ContentKey.of("test_namespace"),
                batchSize > 1 ? "ERROR" : "OK",
                batchSize > 1,
                batchSize > 1),
            tuple(ContentKey.of("test_namespace", "table_" + CID_1), "ERROR", true, true),
            tuple(ContentKey.of("test_namespace", "table_" + CID_2), "ERROR", true, true),
            tuple(ContentKey.of("test_namespace", "table_" + CID_3), "ERROR", true, true),
            tuple(ContentKey.of("test_namespace", "table_" + CID_4), "ERROR", true, true));
    assertThat(outer.result.exitCode()).isEqualTo(EXIT_CODE_CONTENT_ERROR);
    assertThat(outer.result.getOutputStream())
        .contains(format("Detected %d errors in 5 keys.", batchSize > 1 ? 5 : 4));
  }

  @Test
  public void testValidData(QuarkusMainLauncher launcher) throws Exception {

    outer.commit(table1);
    outer.commit(table2);

    outer.launch(launcher, "check-content", "--show-content");
    assertThat(outer.entries.stream())
        .extracting(
            CheckContentEntry::getKey, CheckContentEntry::getStatus, CheckContentEntry::getContent)
        .containsExactlyInAnyOrder(
            tuple(ContentKey.of("test_namespace"), "OK", outer.namespace),
            tuple(ContentKey.of("test_namespace", "table_" + CID_1), "OK", table1),
            tuple(ContentKey.of("test_namespace", "table_" + CID_2), "OK", table2));
    assertThat(outer.result.exitCode()).isEqualTo(0);
  }

  @Test
  public void testValidDataDeletedKey(QuarkusMainLauncher launcher) throws Exception {

    outer.commit(table1); // PUT
    outer.commit(table1, false); // DELETE

    outer.launch(launcher, "check-content", "--show-content");
    assertThat(outer.entries.stream())
        .extracting(
            CheckContentEntry::getKey, CheckContentEntry::getStatus, CheckContentEntry::getContent)
        .containsExactly(tuple(ContentKey.of("test_namespace"), "OK", outer.namespace));
    assertThat(outer.result.exitCode()).isEqualTo(0);
  }

  @Test
  public void testValidDataNoContent(QuarkusMainLauncher launcher) throws Exception {
    outer.commit(table1);
    outer.commit(table2);

    outer.launch(launcher, "check-content");
    assertThat(outer.entries).hasSize(3);
    assertThat(outer.entries).allSatisfy(e -> assertThat(e.getContent()).isNull());
    assertThat(outer.result.exitCode()).isEqualTo(0);
  }

  @Test
  public void testValidDataStdOut(QuarkusMainLauncher launcher) throws Exception {
    outer.commit(table1);
    outer.commit(table2);

    outer.launchNoFile(
        launcher, "check-content", "-s", "--show-content", "--output", "-"); // '-' for STDOUT
    assertThat(outer.result.getOutputStream())
        .anySatisfy(line -> assertThat(line).contains("table_" + CID_1));
    assertThat(outer.result.getOutputStream())
        .anySatisfy(line -> assertThat(line).contains("table_" + CID_2));
    assertThat(outer.result.getOutputStream())
        .anySatisfy(line -> assertThat(line).contains("meta_111"));
    assertThat(outer.result.getOutputStream())
        .anySatisfy(line -> assertThat(line).contains("meta_222"));
    assertThat(outer.result.exitCode()).isEqualTo(0);
    assertThat(outer.result.getOutputStream()).contains("Detected 0 errors in 3 keys.");
  }

  @Test
  public void testErrorOnly(QuarkusMainLauncher launcher) throws Exception {
    outer.commit(table1);
    outer.launch(launcher, "check-content", "--error-only");
    assertThat(outer.entries).hasSize(0);
    assertThat(outer.result.exitCode()).isEqualTo(0);
  }

  @Test
  public void testHashWithBrokenCommit(QuarkusMainLauncher launcher) throws Exception {
    outer.commit(table1);

    Hash hash = outer.getMainHead();

    UUID contentId = UUID.randomUUID();
    OnRefOnly val = onRef("123", contentId.toString());
    outer.commit(
        ContentKey.of("test_namespace", "table_" + val.getId()),
        contentId,
        (byte) payloadForContent(val),
        val.serialized(),
        true,
        true);

    outer.launch(launcher, "check-content", "--hash", hash.asString());
    assertThat(outer.entries.stream())
        .extracting(CheckContentEntry::getKey, CheckContentEntry::getStatus)
        .containsExactlyInAnyOrder(
            tuple(ContentKey.of("test_namespace"), "OK"),
            tuple(ContentKey.of("test_namespace", "table_" + CID_1), "OK"));
    assertThat(outer.result.exitCode()).isEqualTo(0);
  }
}
