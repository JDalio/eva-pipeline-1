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
package uk.ac.ebi.eva.pipeline.io.readers;

import org.opencb.biodata.formats.variant.vcf4.io.VariantVcfReader;
import org.opencb.biodata.models.variant.VariantSource;
import org.opencb.biodata.models.variant.VariantStudy;
import org.springframework.batch.item.ItemReader;

import uk.ac.ebi.eva.commons.models.data.VariantSourceEntity;

import java.io.File;

public class VcfHeaderReader implements ItemReader<VariantSourceEntity> {

    /**
     * The header of the VCF can be retrieved using `source.getMetadata().get(VARIANT_FILE_HEADER_KEY)`.
     */
    public static final String VARIANT_FILE_HEADER_KEY = "header";

    private final File file;

    private final VariantSource source;

    private boolean readAlreadyDone;

    public VcfHeaderReader(File file,
                           String fileId,
                           String studyId,
                           String studyName,
                           VariantStudy.StudyType type,
                           VariantSource.Aggregation aggregation) {
        this(file, new VariantSource(file.getName(), fileId, studyId, studyName, type, aggregation));
    }

    public VcfHeaderReader(File file, VariantSource source) {
        this.file = file;
        this.source = source;
        this.readAlreadyDone = false;
    }

    /**
     * Before providing the VariantSource as argument to a VcfReader (that uses the VariantVcfFactory inside 
     * the mapper), we need to fill some attributes from the header:
     * <ul>
     * <li>Common VCF fields (FORMAT, INFO, ALT, FILTER, contig)</li>
     * <li>Other fields (may be custom fields from users: reference, source...)</li>
     * <li>Sample names</li>
     * <li>Full header string</li>
     * </ul>
     * <p>
     * As some tags from the header will appear more than once (INFO, contig, ALT...), they are stored in a Map
     * where the key is the field tag, and the value is a list of lines:
     * <p>
     * {@code INFO -> [{ "id" : "CIEND", "number" : "2", "type" : "Integer", "description" : "Confidence..." }, ... ]}
     * <p>
     * We are breaking retrocompatibility here, since the previous structure was wrong. In fields that are different but
     * start with the same key, only the last line was stored, e.g.: {@literal "ALT" : "ID=CN124,Description=...\>"}.
     * Now ALT would map to a list of deconstructed objects: {@code ALT -> [ {id, description}, ...] }
     * <p>
     * Look at the test to see how is this checked.
     */
    @Override
    public VariantSourceEntity read() throws Exception {
        if (readAlreadyDone) {
            return null;
        } else {
            VariantVcfReader reader = new VariantVcfReader(source, file.getPath());
            reader.open();
            reader.pre();
            reader.post();
            reader.close();

            source.addMetadata(VARIANT_FILE_HEADER_KEY, reader.getHeader());
            readAlreadyDone = true;
            return new VariantSourceEntity(source);
        }
    }
}
