/*
 * Copyright (C) 2020 Dremio
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
package org.projectnessie.versioned.persist.mongodb;

import static org.projectnessie.versioned.persist.adapter.serialize.ProtoSerialization.attachmentKeyAsString;
import static org.projectnessie.versioned.persist.adapter.serialize.ProtoSerialization.protoToKeyList;
import static org.projectnessie.versioned.persist.adapter.serialize.ProtoSerialization.toProto;

import com.google.common.collect.Maps;
import com.google.protobuf.InvalidProtocolBufferException;
import com.mongodb.DuplicateKeyException;
import com.mongodb.ErrorCategory;
import com.mongodb.MongoBulkWriteException;
import com.mongodb.MongoServerException;
import com.mongodb.MongoWriteException;
import com.mongodb.bulk.BulkWriteError;
import com.mongodb.bulk.BulkWriteResult;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOneModel;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import com.mongodb.client.model.WriteModel;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.InsertManyResult;
import com.mongodb.client.result.InsertOneResult;
import com.mongodb.client.result.UpdateResult;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.Spliterator;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.Binary;
import org.projectnessie.versioned.Hash;
import org.projectnessie.versioned.NamedRef;
import org.projectnessie.versioned.ReferenceConflictException;
import org.projectnessie.versioned.ReferenceNotFoundException;
import org.projectnessie.versioned.persist.adapter.CommitLogEntry;
import org.projectnessie.versioned.persist.adapter.KeyList;
import org.projectnessie.versioned.persist.adapter.KeyListEntity;
import org.projectnessie.versioned.persist.adapter.KeyListEntry;
import org.projectnessie.versioned.persist.adapter.RefLog;
import org.projectnessie.versioned.persist.adapter.RepoDescription;
import org.projectnessie.versioned.persist.adapter.events.AdapterEventConsumer;
import org.projectnessie.versioned.persist.adapter.serialize.ProtoSerialization;
import org.projectnessie.versioned.persist.adapter.serialize.ProtoSerialization.Parser;
import org.projectnessie.versioned.persist.adapter.spi.DatabaseAdapterUtil;
import org.projectnessie.versioned.persist.nontx.NonTransactionalDatabaseAdapter;
import org.projectnessie.versioned.persist.nontx.NonTransactionalDatabaseAdapterConfig;
import org.projectnessie.versioned.persist.nontx.NonTransactionalOperationContext;
import org.projectnessie.versioned.persist.serialize.AdapterTypes;
import org.projectnessie.versioned.persist.serialize.AdapterTypes.AttachmentKey;
import org.projectnessie.versioned.persist.serialize.AdapterTypes.AttachmentValue;
import org.projectnessie.versioned.persist.serialize.AdapterTypes.ContentId;
import org.projectnessie.versioned.persist.serialize.AdapterTypes.GlobalStateLogEntry;
import org.projectnessie.versioned.persist.serialize.AdapterTypes.GlobalStatePointer;
import org.projectnessie.versioned.persist.serialize.AdapterTypes.NamedReference;
import org.projectnessie.versioned.persist.serialize.AdapterTypes.RefLogParents;
import org.projectnessie.versioned.persist.serialize.AdapterTypes.RefPointer;
import org.projectnessie.versioned.persist.serialize.AdapterTypes.ReferenceNames;
import org.projectnessie.versioned.persist.serialize.AdapterTypes.RepoProps;

public class MongoDatabaseAdapter
    extends NonTransactionalDatabaseAdapter<NonTransactionalDatabaseAdapterConfig> {

  private static final String ID_PROPERTY_NAME = "_id";
  private static final String ID_REPO_NAME = "repo";
  private static final String ID_HASH_NAME = "hash";
  private static final String ID_REF_NAME = "ref";
  private static final String ID_STRIPE = "stripe";
  private static final String ID_ATTR_CONTENT_ID = "cid";
  private static final String ID_ATTR_OBJECT_TYPE = "type";
  private static final String ID_ATTR_OBJECT_ID = "oid";
  private static final String ID_REPO_PATH = ID_PROPERTY_NAME + "." + ID_REPO_NAME;
  private static final String DATA_PROPERTY_NAME = "data";
  private static final String GLOBAL_ID_PROPERTY_NAME = "globalId";
  private static final String LOCK_ID_PROPERTY_NAME = "lockId";
  private static final String VALUE_VERSION_PROPERTY_NAME = "version";

  private final String repositoryId;
  private final String globalPointerKey;

  private final MongoDatabaseClient client;

  protected MongoDatabaseAdapter(
      NonTransactionalDatabaseAdapterConfig config,
      MongoDatabaseClient client,
      AdapterEventConsumer eventConsumer) {
    super(config, eventConsumer);

    Objects.requireNonNull(client, "MongoDatabaseClient cannot be null");
    this.client = client;

    this.repositoryId = "my_dummy_id"; // config.getRepositoryId();
    Objects.requireNonNull(repositoryId, "Repository ID cannot be null");
    System.out.println("repoId: " + repositoryId);

    globalPointerKey = repositoryId;
  }

  @Override
  protected void doEraseRepo() {
    client.getGlobalPointers().deleteMany(Filters.eq(globalPointerKey));
    client.getRepoDesc().deleteMany(Filters.eq(globalPointerKey));
    // filter composite key in a way that utilizes the default index - see https://stackoverflow.com/a/41604577
    Bson repositoryFilter = Filters.eq(Filters.eq(ID_REPO_NAME, repositoryId));
    System.out.println(repositoryFilter);
    client.allWithCompositeId().forEach(coll -> coll.deleteMany(repositoryFilter));
  }

  private Document toId(Hash id) {
    Document idDoc = new Document();
    // Note: the order of `put` calls matters
    idDoc.put(ID_REPO_NAME, repositoryId);
    idDoc.put(ID_HASH_NAME, id.asString());
    return idDoc;
  }

  private Document toId(String id) {
    Document idDoc = new Document();
    // Note: the order of `put` calls matters
    idDoc.put(ID_REPO_NAME, repositoryId);
    idDoc.put(ID_REF_NAME, id);
    return idDoc;
  }

  private Document toId(int id) {
    Document idDoc = new Document();
    // Note: the order of `put` calls matters
    idDoc.put(ID_REPO_NAME, repositoryId);
    idDoc.put(ID_STRIPE, id);
    return idDoc;
  }

  private Document toId(AttachmentKey id) {
    Document idDoc = new Document();
    // Note: the order of `put` calls matters
    idDoc.put(ID_REPO_NAME, repositoryId);
    idDoc.put(ID_ATTR_CONTENT_ID, id.getContentId().getId());
    idDoc.put(ID_ATTR_OBJECT_TYPE, id.getAttachmentType());
    idDoc.put(ID_ATTR_OBJECT_ID, id.getAttachmentId());
    return idDoc;
  }

  private Document toIdAttachmentKeyContentId(String contentId) {
    Document idDoc = new Document();
    // Note: the order of `put` calls matters
    idDoc.put(ID_REPO_NAME, repositoryId);
    idDoc.put(ID_ATTR_CONTENT_ID, contentId);
    return idDoc;
  }

  private List<Document> toIdsFromHashes(Collection<Hash> ids) {
    return ids.stream().map(this::toId).collect(Collectors.toList());
  }

  private List<Document> toIdsFromAttachmentKeys(Stream<AttachmentKey> ids) {
    return ids.map(this::toId).collect(Collectors.toList());
  }

  private Document toDoc(AttachmentKey id, byte[] data) {
    return toDoc(toId(id), data);
  }

  private Document toDoc(Hash id, byte[] data) {
    return toDoc(toId(id), data);
  }

  private static Document toDoc(Document id, byte[] data) {
    Document doc = new Document();
    doc.put(ID_PROPERTY_NAME, id);
    doc.put(DATA_PROPERTY_NAME, data);
    return doc;
  }

  private Document toDoc(GlobalStatePointer pointer) {
    Document doc = new Document();
    doc.put(ID_PROPERTY_NAME, globalPointerKey);
    doc.put(DATA_PROPERTY_NAME, pointer.toByteArray());
    doc.put(GLOBAL_ID_PROPERTY_NAME, pointer.getGlobalId().toByteArray());
    return doc;
  }

  private Document toDoc(RepoProps pointer) {
    Document doc = new Document();
    doc.put(ID_PROPERTY_NAME, globalPointerKey);
    doc.put(DATA_PROPERTY_NAME, pointer.toByteArray());
    return doc;
  }

  private Document toDoc(int stripe, RefLogParents pointer) {
    Document doc = new Document();
    doc.put(ID_PROPERTY_NAME, toId(stripe));
    doc.put(DATA_PROPERTY_NAME, pointer.toByteArray());
    doc.put(LOCK_ID_PROPERTY_NAME, pointer.getVersion().toByteArray());
    return doc;
  }

  private Document toDoc(NamedReference pointer) {
    Document doc = new Document();
    doc.put(ID_PROPERTY_NAME, toId(pointer.getName()));
    doc.put(DATA_PROPERTY_NAME, pointer.toByteArray());
    doc.put(ID_HASH_NAME, pointer.getRef().getHash().toByteArray());
    return doc;
  }

  private void insert(MongoCollection<Document> collection, Hash id, byte[] data)
      throws ReferenceConflictException {
    insert(collection, toDoc(id, data));
  }

  private static void insert(MongoCollection<Document> collection, Document doc)
      throws ReferenceConflictException {
    InsertOneResult result;
    try {
      result = collection.insertOne(doc);
    } catch (MongoServerException e) {
      if (isDuplicateKeyError(e)) {
        ReferenceConflictException ex = DatabaseAdapterUtil.hashCollisionDetected();
        ex.initCause(e);
        throw ex;
      }
      throw e;
    }

    verifyAcknowledged(result, collection);
  }

  private static void insert(MongoCollection<Document> collection, List<Document> docs)
      throws ReferenceConflictException {
    if (docs.isEmpty()) {
      return; // Mongo does not accept empty args to insertMany()
    }

    InsertManyResult result;
    try {
      result = collection.insertMany(docs);
    } catch (MongoServerException e) {
      if (isDuplicateKeyError(e)) {
        ReferenceConflictException ex = DatabaseAdapterUtil.hashCollisionDetected();
        ex.initCause(e);
        throw ex;
      }
      throw e;
    }

    verifyAcknowledged(result, collection);
  }

  private static void bulkWrite(
      MongoCollection<Document> collection, List<WriteModel<Document>> requests) {
    verifyAcknowledged(collection.bulkWrite(requests), collection);
  }

  private void delete(MongoCollection<Document> collection, Collection<Hash> ids) {
    DeleteResult result = collection.deleteMany(Filters.in(ID_PROPERTY_NAME, toIdsFromHashes(ids)));
    verifyAcknowledged(result, collection);
  }

  private void delete(MongoCollection<Document> collection, Stream<AttachmentKey> ids) {
    DeleteResult result =
        collection.deleteMany(Filters.in(ID_PROPERTY_NAME, toIdsFromAttachmentKeys(ids)));
    verifyAcknowledged(result, collection);
  }

  private static <ID> byte[] loadById(MongoCollection<Document> collection, ID id) {
    Document doc = collection.find(Filters.eq(id)).first();
    if (doc == null) {
      return null;
    }

    Binary data = doc.get(DATA_PROPERTY_NAME, Binary.class);
    if (data == null) {
      return null;
    }

    return data.getData();
  }

  private <T> T loadById(MongoCollection<Document> collection, Hash id, Parser<T> parser) {
    return loadByIdGeneric(collection, toId(id), parser);
  }

  private <T> T loadById(MongoCollection<Document> collection, String ref, Parser<T> parser) {
    return loadByIdGeneric(collection, toId(ref), parser);
  }

  private <T> T loadById(MongoCollection<Document> collection, int stripe, Parser<T> parser) {
    return loadByIdGeneric(collection, toId(stripe), parser);
  }

  private static <T, ID> T loadByIdGeneric(
      MongoCollection<Document> collection, ID id, Parser<T> parser) {
    byte[] data = loadById(collection, id);
    if (data == null) {
      return null;
    }

    try {
      return parser.parse(data);
    } catch (InvalidProtocolBufferException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Fetches a collection of documents by hash and returns them in the order of hashes requested.
   *
   * <p>Note: unknown hashes will correspond to {@code null} elements in the result list.
   */
  private <T> List<T> fetchMappedPage(
      MongoCollection<Document> collection, List<Hash> hashes, Function<Document, T> mapper) {
    List<Document> ids = hashes.stream().map(this::toId).collect(Collectors.toList());
    FindIterable<Document> docs =
        collection.find(Filters.in(ID_PROPERTY_NAME, ids)).limit(hashes.size());

    Map<Hash, Document> loaded = Maps.newHashMapWithExpectedSize(hashes.size());
    for (Document doc : docs) {
      loaded.put(idAsHash(doc), doc);
    }

    List<T> result = new ArrayList<>(hashes.size());
    for (Hash hash : hashes) {
      T element = null;
      Document document = loaded.get(hash);
      if (document != null) {
        element = mapper.apply(document);
      }

      result.add(element); // nulls elements are permitted
    }

    return result;
  }

  private <T> List<T> fetchPage(
      MongoCollection<Document> collection, List<Hash> hashes, Parser<T> parser) {
    return fetchMappedPage(
        collection,
        hashes,
        document -> {
          try {
            byte[] data = data(document);
            return parser.parse(data);
          } catch (InvalidProtocolBufferException e) {
            throw new IllegalStateException(e);
          }
        });
  }

  @Override
  protected CommitLogEntry doFetchFromCommitLog(NonTransactionalOperationContext ctx, Hash hash) {
    return loadById(client.getCommitLog(), hash, ProtoSerialization::protoToCommitLogEntry);
  }

  private Hash idAsHash(Document doc) {
    return Hash.of(idAsString(doc));
  }

  private String idAsString(Document doc) {
    Document id = doc.get(ID_PROPERTY_NAME, Document.class);

    String repo = id.getString(ID_REPO_NAME);
    if (!repositoryId.equals(repo)) {
      throw new IllegalStateException(
          String.format(
              "Repository mismatch for id '%s' (expected repository ID: '%s')", id, repositoryId));
    }

    return id.getString(ID_HASH_NAME);
  }

  private static byte[] data(Document doc) {
    return doc.get(DATA_PROPERTY_NAME, Binary.class).getData();
  }

  @Override
  protected List<CommitLogEntry> doFetchMultipleFromCommitLog(
      NonTransactionalOperationContext ctx, List<Hash> hashes) {
    return fetchPage(client.getCommitLog(), hashes, ProtoSerialization::protoToCommitLogEntry);
  }

  @Override
  protected RepoDescription doFetchRepositoryDescription(NonTransactionalOperationContext ctx) {
    return loadByIdGeneric(
        client.getRepoDesc(), globalPointerKey, ProtoSerialization::protoToRepoDescription);
  }

  @Override
  protected boolean doTryUpdateRepositoryDescription(
      NonTransactionalOperationContext ctx, RepoDescription expected, RepoDescription updateTo) {
    Document doc = toDoc(toProto(updateTo));

    if (expected != null) {
      byte[] expectedBytes = toProto(expected).toByteArray();

      return verifySuccessfulUpdate(
          client.getRepoDesc(),
          coll ->
              coll.replaceOne(
                  Filters.and(
                      Filters.eq(globalPointerKey), Filters.eq(DATA_PROPERTY_NAME, expectedBytes)),
                  doc));
    } else {
      try {
        return client.getRepoDesc().insertOne(doc).wasAcknowledged();
      } catch (MongoServerException e) {
        if (isDuplicateKeyError(e)) {
          return false;
        }
        throw e;
      }
    }
  }

  @Override
  protected void unsafeWriteRefLogStripe(
      NonTransactionalOperationContext ctx, int stripe, RefLogParents refLogParents) {
    Document doc = toDoc(stripe, refLogParents);

    verifyAcknowledged(
        client
            .getRefLogHeads()
            .updateOne(
                Filters.eq(toId(stripe)),
                new Document("$set", doc),
                new UpdateOptions().upsert(true)),
        client.getRefLogHeads());
  }

  @Override
  protected boolean doRefLogParentsCas(
      NonTransactionalOperationContext ctx,
      int stripe,
      RefLogParents previousEntry,
      RefLogParents newEntry) {
    Document doc = toDoc(stripe, newEntry);

    if (previousEntry != null) {
      byte[] expectedLockId = previousEntry.getVersion().toByteArray();
      return verifySuccessfulUpdate(
          client.getRefLogHeads(),
          coll ->
              coll.replaceOne(
                  Filters.and(
                      Filters.eq(toId(stripe)), Filters.eq(LOCK_ID_PROPERTY_NAME, expectedLockId)),
                  doc));
    } else {
      try {
        verifyAcknowledged(client.getRefLogHeads().insertOne(doc), client.getRefLogHeads());
        return true;
      } catch (MongoServerException e) {
        if (isDuplicateKeyError(e)) {
          return false;
        }
        throw e;
      }
    }
  }

  @Override
  protected RefLogParents doFetchRefLogParents(NonTransactionalOperationContext ctx, int stripe) {
    return loadById(client.getRefLogHeads(), stripe, RefLogParents::parseFrom);
  }

  @Override
  protected List<NamedReference> doFetchNamedReference(
      NonTransactionalOperationContext ctx, List<String> refNames) {

    List<Document> idDocs = refNames.stream().map(this::toId).collect(Collectors.toList());

    List<NamedReference> result = new ArrayList<>(refNames.size());

    client
        .getRefHeads()
        .find(Filters.in(ID_PROPERTY_NAME, idDocs))
        .limit(idDocs.size())
        .map(doc -> doc.get(DATA_PROPERTY_NAME, Binary.class).getData())
        .map(
            data -> {
              try {
                return NamedReference.parseFrom(data);
              } catch (InvalidProtocolBufferException e) {
                throw new RuntimeException(e);
              }
            })
        .forEach(result::add);

    return result;
  }

  @Override
  protected boolean doCreateNamedReference(
      NonTransactionalOperationContext ctx, NamedReference namedReference) {
    byte[] existing = loadById(client.getRefHeads(), namedReference.getName(), bytes -> bytes);
    if (existing != null) {
      return false;
    }

    try {
      verifyAcknowledged(
          client.getRefHeads().insertOne(toDoc(namedReference)), client.getRefHeads());
      return true;
    } catch (MongoServerException e) {
      if (isDuplicateKeyError(e)) {
        return false;
      }
      throw e;
    }
  }

  @Override
  protected boolean doDeleteNamedReference(
      NonTransactionalOperationContext ctx, NamedRef ref, RefPointer refHead) {
    return verifyAcknowledged(
                client
                    .getRefHeads()
                    .deleteOne(
                        Filters.and(
                            Filters.eq(toId(ref.getName())),
                            Filters.eq(ID_HASH_NAME, refHead.getHash().toByteArray()))),
                client.getRefHeads())
            .getDeletedCount()
        == 1;
  }

  @Override
  protected void doAddToNamedReferences(
      NonTransactionalOperationContext ctx, Stream<NamedRef> refStream, int addToSegment) {
    verifyAcknowledged(
        client
            .getRefNames()
            .updateOne(
                Filters.eq(toId(addToSegment)),
                Updates.addEachToSet(
                    DATA_PROPERTY_NAME,
                    refStream.map(NamedRef::getName).collect(Collectors.toList())),
                new UpdateOptions().upsert(true)),
        client.getRefNames());
  }

  @Override
  protected void doRemoveFromNamedReferences(
      NonTransactionalOperationContext ctx, NamedRef ref, int removeFromSegment) {
    verifyAcknowledged(
        client
            .getRefNames()
            .updateOne(
                Filters.eq(toId(removeFromSegment)),
                Updates.pull(DATA_PROPERTY_NAME, ref.getName()),
                new UpdateOptions().upsert(true)),
        client.getRefNames());
  }

  @Override
  protected boolean doUpdateNamedReference(
      NonTransactionalOperationContext ctx, NamedRef ref, RefPointer refHead, Hash newHead) {
    Document newDoc =
        toDoc(
            NamedReference.newBuilder()
                .setName(ref.getName())
                .setRef(refHead.toBuilder().setHash(newHead.asBytes()))
                .build());
    return verifySuccessfulUpdate(
        client.getRefHeads(),
        coll ->
            coll.updateOne(
                Filters.and(
                    Filters.eq(toId(ref.getName())),
                    Filters.eq(ID_HASH_NAME, refHead.getHash().toByteArray())),
                new Document("$set", newDoc)));
  }

  @Override
  protected int entitySize(CommitLogEntry entry) {
    return toProto(entry).getSerializedSize();
  }

  @Override
  protected int entitySize(KeyListEntry entry) {
    return toProto(entry).getSerializedSize();
  }

  @Override
  protected Stream<KeyListEntity> doFetchKeyLists(
      NonTransactionalOperationContext ctx, List<Hash> keyListsIds) {
    return fetchMappedPage(
        client.getKeyLists(),
        keyListsIds,
        document -> {
          Hash hash = idAsHash(document);
          KeyList keyList = protoToKeyList(data(document));
          return KeyListEntity.of(hash, keyList);
        })
        .stream();
  }

  @Override
  protected void doWriteIndividualCommit(NonTransactionalOperationContext ctx, CommitLogEntry entry)
      throws ReferenceConflictException {
    insert(client.getCommitLog(), entry.getHash(), toProto(entry).toByteArray());
  }

  @Override
  protected void doWriteMultipleCommits(
      NonTransactionalOperationContext ctx, List<CommitLogEntry> entries)
      throws ReferenceConflictException {
    List<Document> docs =
        entries.stream()
            .map(e -> toDoc(e.getHash(), toProto(e).toByteArray()))
            .collect(Collectors.toList());
    insert(client.getCommitLog(), docs);
  }

  @Override
  protected void doUpdateMultipleCommits(
      NonTransactionalOperationContext ctx, List<CommitLogEntry> entries)
      throws ReferenceNotFoundException {
    List<WriteModel<Document>> requests =
        entries.stream()
            .map(e -> toDoc(e.getHash(), toProto(e).toByteArray()))
            .map(
                d ->
                    new UpdateOneModel<Document>(
                        Filters.eq(d.get(ID_PROPERTY_NAME)),
                        new Document("$set", d),
                        new UpdateOptions().upsert(false)))
            .collect(Collectors.toList());

    BulkWriteResult result = client.getCommitLog().bulkWrite(requests);

    verifyAcknowledged(result, client.getCommitLog());

    if (result.getMatchedCount() != entries.size()) {
      throw new ReferenceNotFoundException("");
    }
  }

  @Override
  protected void doWriteKeyListEntities(
      NonTransactionalOperationContext ctx, List<KeyListEntity> newKeyListEntities) {
    try {
      List<Document> docs =
          newKeyListEntities.stream()
              .map(keyList -> toDoc(keyList.getId(), toProto(keyList.getKeys()).toByteArray()))
              .collect(Collectors.toList());
      insert(client.getKeyLists(), docs);
    } catch (ReferenceConflictException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  protected void unsafeWriteGlobalPointer(
      NonTransactionalOperationContext ctx, GlobalStatePointer pointer) {
    Document doc = toDoc(pointer);

    verifyAcknowledged(
        client
            .getGlobalPointers()
            .updateOne(
                Filters.eq((Object) globalPointerKey),
                new Document("$set", doc),
                new UpdateOptions().upsert(true)),
        client.getGlobalPointers());
  }

  @Override
  protected boolean doGlobalPointerCas(
      NonTransactionalOperationContext ctx,
      GlobalStatePointer expected,
      GlobalStatePointer newPointer) {
    Document doc = toDoc(newPointer);
    byte[] expectedGlobalId = expected.getGlobalId().toByteArray();

    return verifySuccessfulUpdate(
        client.getGlobalPointers(),
        coll ->
            coll.replaceOne(
                Filters.and(
                    Filters.eq(globalPointerKey),
                    Filters.eq(GLOBAL_ID_PROPERTY_NAME, expectedGlobalId)),
                doc));
  }

  @Override
  protected void doCleanUpCommitCas(
      NonTransactionalOperationContext ctx, Set<Hash> branchCommits, Set<Hash> newKeyLists) {
    delete(client.getCommitLog(), branchCommits);
    delete(client.getKeyLists(), newKeyLists);
  }

  @Override
  protected void doCleanUpRefLogWrite(NonTransactionalOperationContext ctx, Hash refLogId) {
    client.getRefLog().deleteOne(Filters.eq(toId(refLogId)));
  }

  @Override
  protected List<ReferenceNames> doFetchReferenceNames(
      NonTransactionalOperationContext ctx, int segment, int prefetchSegments) {
    List<Document> idDocs =
        IntStream.rangeClosed(segment, segment + prefetchSegments)
            .mapToObj(this::toId)
            .collect(Collectors.toList());

    ReferenceNames[] result = new ReferenceNames[1 + prefetchSegments];

    client
        .getRefNames()
        .find(Filters.in(ID_PROPERTY_NAME, idDocs))
        .forEach(
            doc -> {
              Integer stripe =
                  doc.get(ID_PROPERTY_NAME, Document.class).get(ID_STRIPE, Integer.class);
              ReferenceNames refNames =
                  ReferenceNames.newBuilder()
                      .addAllRefNames(doc.getList(DATA_PROPERTY_NAME, String.class))
                      .build();
              result[stripe - segment] = refNames;
            });

    return Arrays.asList(result);
  }

  @Override
  protected GlobalStatePointer doFetchGlobalPointer(NonTransactionalOperationContext ctx) {
    return loadByIdGeneric(
        client.getGlobalPointers(), globalPointerKey, GlobalStatePointer::parseFrom);
  }

  @Override
  protected GlobalStateLogEntry doFetchFromGlobalLog(
      NonTransactionalOperationContext ctx, Hash id) {
    return loadById(client.getGlobalLog(), id, GlobalStateLogEntry::parseFrom);
  }

  @Override
  protected List<GlobalStateLogEntry> doFetchPageFromGlobalLog(
      NonTransactionalOperationContext ctx, List<Hash> hashes) {
    return fetchPage(client.getGlobalLog(), hashes, GlobalStateLogEntry::parseFrom);
  }

  @Override
  protected void doWriteRefLog(NonTransactionalOperationContext ctx, AdapterTypes.RefLogEntry entry)
      throws ReferenceConflictException {
    Document id = toId(Hash.of(entry.getRefLogId()));
    insert(client.getRefLog(), toDoc(id, entry.toByteArray()));
  }

  @Override
  protected RefLog doFetchFromRefLog(NonTransactionalOperationContext ctx, Hash refLogId) {
    Objects.requireNonNull(refLogId, "refLogId mut not be null");
    return loadById(client.getRefLog(), refLogId, ProtoSerialization::protoToRefLog);
  }

  @Override
  protected List<RefLog> doFetchPageFromRefLog(
      NonTransactionalOperationContext ctx, List<Hash> hashes) {
    return fetchPage(client.getRefLog(), hashes, ProtoSerialization::protoToRefLog);
  }

  @Override
  protected Stream<CommitLogEntry> doScanAllCommitLogEntries(NonTransactionalOperationContext c) {
    Bson idPrefixFilter = Filters.eq(ID_REPO_PATH, repositoryId);
    FindIterable<Document> iter =
        client
            .getCommitLog()
            .find(idPrefixFilter, Document.class)
            .batchSize(config.getCommitLogScanPrefetch());
    Spliterator<Document> split = iter.spliterator();
    return StreamSupport.stream(split, false)
        .map(doc -> doc.get(DATA_PROPERTY_NAME, Binary.class))
        .map(Binary::getData)
        .map(ProtoSerialization::protoToCommitLogEntry);
  }

  private static boolean isDuplicateKeyError(MongoServerException e) {
    if (e instanceof DuplicateKeyException) {
      return true;
    }
    if (e instanceof MongoWriteException) {
      MongoWriteException writeException = (MongoWriteException) e;
      return writeException.getError().getCategory() == ErrorCategory.DUPLICATE_KEY;
    }
    if (e instanceof MongoBulkWriteException) {
      MongoBulkWriteException writeException = (MongoBulkWriteException) e;
      for (BulkWriteError writeError : writeException.getWriteErrors()) {
        if (writeError.getCategory() == ErrorCategory.DUPLICATE_KEY) {
          return true;
        }
      }
    }
    return false;
  }

  @Override
  protected void writeAttachments(Stream<Entry<AttachmentKey, AttachmentValue>> attachments) {
    Map<String, List<AttachmentKey>> attachmentsKeys = new HashMap<>();

    List<WriteModel<Document>> requests =
        attachments
            .map(
                e -> {
                  AttachmentValue value = e.getValue();
                  attachmentsKeys
                      .computeIfAbsent(e.getKey().getContentId().getId(), c -> new ArrayList<>())
                      .add(e.getKey());
                  Document doc = toDoc(e.getKey(), value.toByteArray());
                  if (value.hasVersion()) {
                    doc.put(VALUE_VERSION_PROPERTY_NAME, value.getVersion());
                  }
                  return doc;
                })
            .map(
                doc ->
                    new UpdateOneModel<Document>(
                        Filters.eq(doc.get(ID_PROPERTY_NAME)),
                        new Document("$set", doc),
                        new UpdateOptions().upsert(true)))
            .collect(Collectors.toList());

    bulkWrite(client.getAttachments(), requests);

    requests =
        attachmentsKeys.entrySet().stream()
            .map(
                e ->
                    new UpdateOneModel<Document>(
                        Filters.eq(ID_PROPERTY_NAME, toIdAttachmentKeyContentId(e.getKey())),
                        Updates.addEachToSet(
                            DATA_PROPERTY_NAME,
                            e.getValue().stream()
                                .map(ProtoSerialization::attachmentKeyAsString)
                                .collect(Collectors.toList())),
                        new UpdateOptions().upsert(true)))
            .collect(Collectors.toList());

    bulkWrite(client.getAttachmentKeys(), requests);
  }

  @Override
  protected boolean consistentWriteAttachment(
      AttachmentKey key, AttachmentValue value, Optional<String> expectedVersion) {
    Document doc = toDoc(key, value.toByteArray());
    doc.put(VALUE_VERSION_PROPERTY_NAME, value.getVersion());

    Document id = toId(key);

    if (expectedVersion.isPresent()) {
      if (!verifySuccessfulUpdate(
          client.getAttachments(),
          coll ->
              coll.updateOne(
                  Filters.and(
                      Filters.eq(id),
                      Filters.eq(VALUE_VERSION_PROPERTY_NAME, expectedVersion.get())),
                  new Document("$set", doc)))) {
        return false;
      }
    } else {
      try {
        if (!client.getAttachments().insertOne(doc).wasAcknowledged()) {
          return false;
        }
      } catch (MongoWriteException writeException) {
        if (isDuplicateKeyError(writeException)) {
          return false;
        }
        throw writeException;
      }
    }

    verifyAcknowledged(
        client
            .getAttachmentKeys()
            .updateOne(
                Filters.eq(
                    ID_PROPERTY_NAME, toIdAttachmentKeyContentId(key.getContentId().getId())),
                Updates.addToSet(DATA_PROPERTY_NAME, attachmentKeyAsString(key)),
                new UpdateOptions().upsert(true)),
        client.getAttachmentKeys());

    return true;
  }

  @Override
  protected void purgeAttachments(Stream<AttachmentKey> keys) {
    Map<String, List<AttachmentKey>> attachmentsKeys = new HashMap<>();

    delete(
        client.getAttachments(),
        keys.peek(
            key ->
                attachmentsKeys
                    .computeIfAbsent(key.getContentId().getId(), c -> new ArrayList<>())
                    .add(key)));

    attachmentsKeys.forEach(
        (cid, attachmentKeys) ->
            verifyAcknowledged(
                client
                    .getAttachmentKeys()
                    .updateOne(
                        Filters.eq(ID_PROPERTY_NAME, toIdAttachmentKeyContentId(cid)),
                        Updates.pullAll(
                            DATA_PROPERTY_NAME,
                            attachmentKeys.stream()
                                .map(ProtoSerialization::attachmentKeyAsString)
                                .collect(Collectors.toList())),
                        new UpdateOptions().upsert(true)),
                client.getAttachmentKeys()));
  }

  @Override
  protected Stream<AttachmentKey> fetchAttachmentKeys(String contentId) {
    Document doc =
        client
            .getAttachmentKeys()
            .find(Filters.eq(ID_PROPERTY_NAME, toIdAttachmentKeyContentId(contentId)))
            .first();
    if (doc == null) {
      return Stream.empty();
    }
    List<String> keys = doc.getList(DATA_PROPERTY_NAME, String.class);
    return keys.stream().map(ProtoSerialization::attachmentKeyFromString);
  }

  @Override
  protected Stream<Entry<AttachmentKey, AttachmentValue>> fetchAttachments(
      Stream<AttachmentKey> keys) {

    List<AttachmentKey> keyList = keys.collect(Collectors.toList());
    List<Document> ids = keyList.stream().map(this::toId).collect(Collectors.toList());
    FindIterable<Document> docs =
        client.getAttachments().find(Filters.in(ID_PROPERTY_NAME, ids)).limit(ids.size());

    Map<AttachmentKey, AttachmentValue> loaded = Maps.newHashMapWithExpectedSize(ids.size());
    for (Document doc : docs) {
      Document idDoc = doc.get(ID_PROPERTY_NAME, Document.class);
      AttachmentKey k =
          AttachmentKey.newBuilder()
              .setContentId(ContentId.newBuilder().setId(idDoc.getString(ID_ATTR_CONTENT_ID)))
              .setAttachmentType(idDoc.getString(ID_ATTR_OBJECT_TYPE))
              .setAttachmentId(idDoc.getString(ID_ATTR_OBJECT_ID))
              .build();
      AttachmentValue v;
      try {
        v = AttachmentValue.parseFrom(data(doc));
      } catch (InvalidProtocolBufferException e) {
        throw new RuntimeException(e);
      }
      loaded.put(k, v);
    }

    return keyList.stream()
        .map(
            k -> {
              AttachmentValue v = loaded.get(k);
              return v != null ? Maps.immutableEntry(k, v) : null;
            })
        .filter(Objects::nonNull);
  }

  private static boolean verifySuccessfulUpdate(
      MongoCollection<Document> mongoCollection,
      Function<MongoCollection<Document>, UpdateResult> updater) {
    UpdateResult result = updater.apply(mongoCollection);
    verifyAcknowledged(result, mongoCollection);
    return result.getMatchedCount() == 1 && result.getModifiedCount() == 1;
  }

  private static void verifyAcknowledged(
      InsertOneResult result, MongoCollection<Document> mongoCollection) {
    verifyAcknowledged(result.wasAcknowledged(), mongoCollection);
  }

  private static void verifyAcknowledged(
      InsertManyResult result, MongoCollection<Document> mongoCollection) {
    verifyAcknowledged(result.wasAcknowledged(), mongoCollection);
  }

  private static void verifyAcknowledged(
      BulkWriteResult result, MongoCollection<Document> mongoCollection) {
    verifyAcknowledged(result.wasAcknowledged(), mongoCollection);
  }

  private static void verifyAcknowledged(
      UpdateResult result, MongoCollection<Document> mongoCollection) {
    verifyAcknowledged(result.wasAcknowledged(), mongoCollection);
  }

  private static DeleteResult verifyAcknowledged(
      DeleteResult result, MongoCollection<Document> mongoCollection) {
    verifyAcknowledged(result.wasAcknowledged(), mongoCollection);
    return result;
  }

  private static void verifyAcknowledged(
      boolean acknowledged, MongoCollection<Document> mongoCollection) {
    if (!acknowledged) {
      throw new IllegalStateException("Unacknowledged write to " + mongoCollection.getNamespace());
    }
  }
}
