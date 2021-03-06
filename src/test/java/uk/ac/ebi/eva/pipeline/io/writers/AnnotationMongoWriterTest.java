/*
 * Copyright 2016-2017 EMBL - European Bioinformatics Institute
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.ac.ebi.eva.pipeline.io.writers;

import com.mongodb.BasicDBList;
import com.mongodb.BasicDBObject;
import com.mongodb.DBCollection;
import com.mongodb.DBCursor;
import com.mongodb.DBObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit4.SpringRunner;
import uk.ac.ebi.eva.commons.mongodb.entities.AnnotationMongo;
import uk.ac.ebi.eva.commons.mongodb.entities.subdocuments.ConsequenceTypeMongo;
import uk.ac.ebi.eva.commons.mongodb.entities.subdocuments.ScoreMongo;
import uk.ac.ebi.eva.pipeline.Application;
import uk.ac.ebi.eva.pipeline.configuration.MongoConfiguration;
import uk.ac.ebi.eva.pipeline.io.mappers.AnnotationLineMapper;
import uk.ac.ebi.eva.pipeline.parameters.MongoConnection;
import uk.ac.ebi.eva.test.rules.TemporaryMongoRule;
import uk.ac.ebi.eva.utils.MongoDBHelper;

import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import static uk.ac.ebi.eva.commons.mongodb.entities.AnnotationMongo.CONSEQUENCE_TYPE_FIELD;
import static uk.ac.ebi.eva.commons.mongodb.entities.AnnotationMongo.XREFS_FIELD;
import static uk.ac.ebi.eva.commons.mongodb.entities.subdocuments.ConsequenceTypeMongo.POLYPHEN_FIELD;
import static uk.ac.ebi.eva.commons.mongodb.entities.subdocuments.ConsequenceTypeMongo.SIFT_FIELD;
import static uk.ac.ebi.eva.commons.mongodb.entities.subdocuments.ScoreMongo.SCORE_DESCRIPTION_FIELD;
import static uk.ac.ebi.eva.commons.mongodb.entities.subdocuments.ScoreMongo.SCORE_SCORE_FIELD;
import static uk.ac.ebi.eva.test.data.VepOutputContent.vepOutputContent;
import static uk.ac.ebi.eva.test.utils.JobTestUtils.count;

/**
 * {@link AnnotationMongoWriter}
 * input: a List of Annotation to each call of `.write()`
 * output: all the Annotations get written in mongo, with at least the
 * "consequence types" annotations set
 */
@RunWith(SpringRunner.class)
@ActiveProfiles(Application.VARIANT_ANNOTATION_MONGO_PROFILE)
@TestPropertySource({"classpath:test-mongo.properties"})
@ContextConfiguration(classes = {MongoConnection.class, MongoMappingContext.class})
public class AnnotationMongoWriterTest {

    private static final String COLLECTION_ANNOTATIONS_NAME = "annotations";

    private static final String VEP_VERSION = "1";

    private static final String VEP_CACHE_VERSION = "2";

    @Autowired
    private MongoConnection mongoConnection;

    @Autowired
    private MongoMappingContext mongoMappingContext;

    @Rule
    public TemporaryMongoRule mongoRule = new TemporaryMongoRule();

    private AnnotationMongoWriter annotationWriter;

    private AnnotationLineMapper annotationLineMapper;

    @Before
    public void setUp() throws Exception {
        annotationLineMapper = new AnnotationLineMapper(VEP_VERSION, VEP_CACHE_VERSION);
    }

    @Test
    public void shouldWriteAllFieldsIntoMongoDb() throws Exception {
        String databaseName = mongoRule.getRandomTemporaryDatabaseName();

        List<AnnotationMongo> annotations = new ArrayList<>();
        for (String annotLine : vepOutputContent.split("\n")) {
            annotations.add(annotationLineMapper.mapLine(annotLine, 0));
        }

        // load the annotation
        MongoOperations operations = MongoConfiguration.getMongoOperations(databaseName, mongoConnection,
                                                                           mongoMappingContext);
        annotationWriter = new AnnotationMongoWriter(operations, COLLECTION_ANNOTATIONS_NAME);
        annotationWriter.write(Collections.singletonList(annotations));

        // and finally check that documents in annotation collection have annotations
        DBCursor cursor = mongoRule.getCollection(databaseName, COLLECTION_ANNOTATIONS_NAME).find();

        int count = 0;
        int consequenceTypeCount = 0;
        while (cursor.hasNext()) {
            count++;
            DBObject annotation = cursor.next();
            BasicDBList consequenceTypes = (BasicDBList) annotation.get(CONSEQUENCE_TYPE_FIELD);
            assertNotNull(consequenceTypes);
            consequenceTypeCount += consequenceTypes.size();
        }

        assertTrue(count > 0);
        assertEquals(annotations.size(), consequenceTypeCount);
    }

    /**
     * Test that every Annotation gets written, even if the same variant receives different annotation from
     * different batches.
     *
     * @throws Exception if the annotationWriter.write fails, or the DBs cleaning fails
     */
    @Test
    public void shouldWriteAllFieldsIntoMongoDbMultipleSetsAnnotations() throws Exception {
        String databaseName = mongoRule.getRandomTemporaryDatabaseName();

        //prepare annotation sets
        List<AnnotationMongo> annotationSet1 = new ArrayList<>();
        List<AnnotationMongo> annotationSet2 = new ArrayList<>();
        List<AnnotationMongo> annotationSet3 = new ArrayList<>();

        String[] vepOutputLines = vepOutputContent.split("\n");

        for (String annotLine : Arrays.copyOfRange(vepOutputLines, 0, 2)) {
            annotationSet1.add(annotationLineMapper.mapLine(annotLine, 0));
        }

        for (String annotLine : Arrays.copyOfRange(vepOutputLines, 2, 4)) {
            annotationSet2.add(annotationLineMapper.mapLine(annotLine, 0));
        }

        for (String annotLine : Arrays.copyOfRange(vepOutputLines, 4, 7)) {
            annotationSet3.add(annotationLineMapper.mapLine(annotLine, 0));
        }

        // load the annotation
        MongoOperations operations = MongoConfiguration.getMongoOperations(databaseName, mongoConnection,
                                                                           mongoMappingContext);
        annotationWriter = new AnnotationMongoWriter(operations, COLLECTION_ANNOTATIONS_NAME);

        annotationWriter.write(Collections.singletonList(annotationSet1));
        annotationWriter.write(Collections.singletonList(annotationSet2));
        annotationWriter.write(Collections.singletonList(annotationSet3));

        // and finally check that documents in DB have the correct number of annotation
        DBCursor cursor = mongoRule.getCollection(databaseName, COLLECTION_ANNOTATIONS_NAME).find();

        while (cursor.hasNext()) {
            DBObject annotation = cursor.next();
            String id = annotation.get("_id").toString();

            if (id.equals("20_63360_C_T") || id.equals("20_63399_G_A") || id.equals("20_63426_G_T")) {
                assertEquals(2, ((BasicDBList) annotation.get(CONSEQUENCE_TYPE_FIELD)).size());
                assertEquals(4, ((BasicDBList) annotation.get(XREFS_FIELD)).size());
            }
        }
    }

    @Test
    public void shouldWriteSubstitutionScoresIntoMongoDb() throws Exception {
        String databaseName = mongoRule.getRandomTemporaryDatabaseName();

        AnnotationMongo annotation = new AnnotationMongo("X", 1, 10, "A", "T",
                VEP_VERSION, VEP_CACHE_VERSION);

        ScoreMongo siftScore = new ScoreMongo(0.02, "deleterious");
        ScoreMongo polyphenScore = new ScoreMongo(0.846, "possibly_damaging");

        ConsequenceTypeMongo consequenceType = new ConsequenceTypeMongo(null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        consequenceType.setSift(siftScore);
        consequenceType.setPolyphen(polyphenScore);

//        addConsequenceType is protected in variation-commons
//        annotation.addConsequenceType(consequenceType);

        MongoOperations operations = MongoConfiguration.getMongoOperations(databaseName, mongoConnection,
                                                                           mongoMappingContext);
        annotationWriter = new AnnotationMongoWriter(operations, COLLECTION_ANNOTATIONS_NAME);

        annotationWriter.write(Collections.singletonList(Collections.singletonList(annotation)));

        DBCursor cursor = mongoRule.getCollection(databaseName, COLLECTION_ANNOTATIONS_NAME).find();
        while (cursor.hasNext()) {
            DBObject annotationField = cursor.next();
            BasicDBList consequenceTypes = (BasicDBList) annotationField.get(CONSEQUENCE_TYPE_FIELD);

            assertNotNull(consequenceTypes);

            LinkedHashMap consequenceTypeMap = (LinkedHashMap) consequenceTypes.get(0);

            BasicDBObject sift = (BasicDBObject) consequenceTypeMap.get(SIFT_FIELD);
            BasicDBObject polyphen = (BasicDBObject) consequenceTypeMap.get(POLYPHEN_FIELD);

            assertEquals(sift.getString(SCORE_DESCRIPTION_FIELD), siftScore.getDescription());
            assertEquals(sift.get(SCORE_SCORE_FIELD), siftScore.getScore());

            assertEquals(polyphen.getString(SCORE_DESCRIPTION_FIELD), polyphenScore.getDescription());
            assertEquals(polyphen.get(SCORE_SCORE_FIELD), polyphenScore.getScore());

        }
    }

    @Test
    public void indexesShouldBeCreatedInBackground() throws UnknownHostException {
        String dbName = mongoRule.getRandomTemporaryDatabaseName();
        MongoOperations mongoOperations = MongoConfiguration.getMongoOperations(dbName, mongoConnection, mongoMappingContext);
        DBCollection dbCollection = mongoOperations.getCollection(COLLECTION_ANNOTATIONS_NAME);

        AnnotationMongoWriter writer = new AnnotationMongoWriter(mongoOperations, COLLECTION_ANNOTATIONS_NAME);

        List<DBObject> indexInfo = dbCollection.getIndexInfo();

        Set<String> createdIndexes = indexInfo.stream().map(index -> index.get("name").toString()).collect(Collectors.toSet());
        Set<String> expectedIndexes = new HashSet<>();
        expectedIndexes.addAll(Arrays.asList("ct.so_1", "xrefs.id_1", "_id_"));

        assertEquals(expectedIndexes, createdIndexes);

        indexInfo.stream().filter(index -> !("_id_".equals(index.get("name").toString()))).forEach(index -> assertEquals("true", index.get(MongoDBHelper.BACKGROUND_INDEX).toString()));
    }

    @Test
    public void shouldUpdateFieldsOfExistingAnnotationVersion() throws Exception {
        String databaseName = mongoRule.getRandomTemporaryDatabaseName();

        List<AnnotationMongo> annotations = new ArrayList<>();
        for (String annotLine : vepOutputContent.split("\n")) {
            annotations.add(annotationLineMapper.mapLine(annotLine, 0));
        }

        // load the annotation
        MongoOperations operations = MongoConfiguration.getMongoOperations(databaseName, mongoConnection,
                mongoMappingContext);
        annotationWriter = new AnnotationMongoWriter(operations, COLLECTION_ANNOTATIONS_NAME);
        annotationWriter.write(Collections.singletonList(annotations.subList(1, 2)));

        // check that consequence type was written in the annotation document
        DBCollection annotCollection = mongoRule.getCollection(databaseName, COLLECTION_ANNOTATIONS_NAME);
        assertEquals(1, count(annotCollection.find()));
        assertEquals(1, countConsequenceType(annotCollection.find()));
        assertEquals(3, countXref(annotCollection.find()));

        // check that consequence types were added to that document
        annotationWriter.write(Collections.singletonList(annotations.subList(2, 3)));
        assertEquals(1, count(annotCollection.find()));
        assertEquals(2, countConsequenceType(annotCollection.find()));
        assertEquals(4, countXref(annotCollection.find()));
    }

    @Test
    public void shouldAddAnnotationIfAnnotationVersionIsNotPresent() throws Exception {
        String differentVepVersion = "different_" + VEP_VERSION;
        String differentVepCacheVersion = "different_" + VEP_CACHE_VERSION;
        AnnotationLineMapper differentVersionAnnotationLineMapper = new AnnotationLineMapper(differentVepVersion,
                                                                                             differentVepCacheVersion);
        String databaseName = mongoRule.getRandomTemporaryDatabaseName();

        String annotLine = vepOutputContent.split("\n")[1];
        List<List<AnnotationMongo>> firstVersionAnnotation = Collections.singletonList(Collections.singletonList(
                annotationLineMapper.mapLine(annotLine, 0)));
        List<List<AnnotationMongo>> secondVersionAnnotation = Collections.singletonList((Collections.singletonList(
                differentVersionAnnotationLineMapper.mapLine(annotLine, 0))));

        // load the annotation
        MongoOperations operations = MongoConfiguration.getMongoOperations(databaseName, mongoConnection,
                mongoMappingContext);
        annotationWriter = new AnnotationMongoWriter(operations, COLLECTION_ANNOTATIONS_NAME);
        annotationWriter.write(firstVersionAnnotation);

        // check that consequence type was written in the annotation document
        DBCollection annotCollection = mongoRule.getCollection(databaseName, COLLECTION_ANNOTATIONS_NAME);
        assertEquals(1, annotCollection.count());
        assertEquals(1, countConsequenceType(annotCollection.find()));
        assertEquals(3, countXref(annotCollection.find()));

        // check that consequence types were added to that document
        annotationWriter.write(secondVersionAnnotation);
        assertEquals(2, annotCollection.count());
        assertEquals(2, countConsequenceType(annotCollection.find()));
        assertEquals(6, countXref(annotCollection.find()));
    }

    private int countConsequenceType(DBCursor cursor) {
        return getArrayCount(cursor, CONSEQUENCE_TYPE_FIELD);
    }

    private int getArrayCount(DBCursor cursor, String field) {
        int count = 0;
        while (cursor.hasNext()) {
            DBObject annotation = cursor.next();
            BasicDBList elements = (BasicDBList) annotation.get(field);
            assertNotNull(elements);
            count += elements.size();
        }
        return count;
    }

    private int countXref(DBCursor cursor) {
        return getArrayCount(cursor, XREFS_FIELD);
    }
}
