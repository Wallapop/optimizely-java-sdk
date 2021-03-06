/**
 *
 *    Copyright 2016-2017, 2019, Optimizely and contributors
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package com.optimizely.ab.bucketing;

import com.optimizely.ab.annotations.VisibleForTesting;
import com.optimizely.ab.bucketing.internal.MurmurHash3;
import com.optimizely.ab.config.Experiment;
import com.optimizely.ab.config.Group;
import com.optimizely.ab.config.ProjectConfig;
import com.optimizely.ab.config.TrafficAllocation;
import com.optimizely.ab.config.Variation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import java.util.List;

/**
 * Default Optimizely bucketing algorithm that evenly distributes users using the Murmur3 hash of some provided
 * identifier.
 * <p>
 * The user identifier <i>must</i> be provided in the first data argument passed to
 * {@link #bucket(Experiment, String, ProjectConfig)} and <i>must</i> be non-null and non-empty.
 *
 * @see <a href="https://en.wikipedia.org/wiki/MurmurHash">MurmurHash</a>
 */
@Immutable
public class Bucketer {

    private static final Logger logger = LoggerFactory.getLogger(Bucketer.class);

    private static final int MURMUR_HASH_SEED = 1;

    /**
     * The maximum bucket value (represents 100 Basis Points).
     */
    @VisibleForTesting
    static final int MAX_TRAFFIC_VALUE = 10000;

    private String bucketToEntity(int bucketValue, List<TrafficAllocation> trafficAllocations) {
        int currentEndOfRange;
        for (TrafficAllocation currAllocation : trafficAllocations) {
            currentEndOfRange = currAllocation.getEndOfRange();
            if (bucketValue < currentEndOfRange) {
                // for mutually exclusive bucketing, de-allocated space is represented by an empty string
                if (currAllocation.getEntityId().isEmpty()) {
                    return null;
                }
                return currAllocation.getEntityId();
            }
        }

        return null;
    }

    private Experiment bucketToExperiment(@Nonnull Group group,
                                          @Nonnull String bucketingId,
                                          @Nonnull ProjectConfig projectConfig) {
        // "salt" the bucket id using the group id
        String bucketKey = bucketingId + group.getId();

        List<TrafficAllocation> trafficAllocations = group.getTrafficAllocation();

        int hashCode = MurmurHash3.murmurhash3_x86_32(bucketKey, 0, bucketKey.length(), MURMUR_HASH_SEED);
        int bucketValue = generateBucketValue(hashCode);
        logger.debug("Assigned bucket {} to user with bucketingId \"{}\" during experiment bucketing.", bucketValue, bucketingId);

        String bucketedExperimentId = bucketToEntity(bucketValue, trafficAllocations);
        if (bucketedExperimentId != null) {
            return projectConfig.getExperimentIdMapping().get(bucketedExperimentId);
        }

        // user was not bucketed to an experiment in the group
        return null;
    }

    private Variation bucketToVariation(@Nonnull Experiment experiment,
                                        @Nonnull String bucketingId) {
        // "salt" the bucket id using the experiment id
        String experimentId = experiment.getId();
        String experimentKey = experiment.getKey();
        String combinedBucketId = bucketingId + experimentId;

        List<TrafficAllocation> trafficAllocations = experiment.getTrafficAllocation();

        int hashCode = MurmurHash3.murmurhash3_x86_32(combinedBucketId, 0, combinedBucketId.length(), MURMUR_HASH_SEED);
        int bucketValue = generateBucketValue(hashCode);
        logger.debug("Assigned bucket {} to user with bucketingId \"{}\" when bucketing to a variation.", bucketValue, bucketingId);

        String bucketedVariationId = bucketToEntity(bucketValue, trafficAllocations);
        if (bucketedVariationId != null) {
            Variation bucketedVariation = experiment.getVariationIdToVariationMap().get(bucketedVariationId);
            String variationKey = bucketedVariation.getKey();
            logger.info("User with bucketingId \"{}\" is in variation \"{}\" of experiment \"{}\".", bucketingId, variationKey,
                experimentKey);

            return bucketedVariation;
        }

        // user was not bucketed to a variation
        logger.info("User with bucketingId \"{}\" is not in any variation of experiment \"{}\".", bucketingId, experimentKey);
        return null;
    }

    /**
     * Assign a {@link Variation} of an {@link Experiment} to a user based on hashed value from murmurhash3.
     *
     * @param experiment  The Experiment in which the user is to be bucketed.
     * @param bucketingId string A customer-assigned value used to create the key for the murmur hash.
     * @return Variation the user is bucketed into or null.
     */
    @Nullable
    public Variation bucket(@Nonnull Experiment experiment,
                            @Nonnull String bucketingId,
                            @Nonnull ProjectConfig projectConfig) {
        // ---------- Bucket User ----------
        String groupId = experiment.getGroupId();
        // check whether the experiment belongs to a group
        if (!groupId.isEmpty()) {
            Group experimentGroup = projectConfig.getGroupIdMapping().get(groupId);
            // bucket to an experiment only if group entities are to be mutually exclusive
            if (experimentGroup.getPolicy().equals(Group.RANDOM_POLICY)) {
                Experiment bucketedExperiment = bucketToExperiment(experimentGroup, bucketingId, projectConfig);
                if (bucketedExperiment == null) {
                    logger.info("User with bucketingId \"{}\" is not in any experiment of group {}.", bucketingId, experimentGroup.getId());
                    return null;
                } else {

                }
                // if the experiment a user is bucketed in within a group isn't the same as the experiment provided,
                // don't perform further bucketing within the experiment
                if (!bucketedExperiment.getId().equals(experiment.getId())) {
                    logger.info("User with bucketingId \"{}\" is not in experiment \"{}\" of group {}.", bucketingId, experiment.getKey(),
                        experimentGroup.getId());
                    return null;
                }

                logger.info("User with bucketingId \"{}\" is in experiment \"{}\" of group {}.", bucketingId, experiment.getKey(),
                    experimentGroup.getId());
            }
        }

        return bucketToVariation(experiment, bucketingId);
    }


    //======== Helper methods ========//

    /**
     * Map the given 32-bit hashcode into the range [0, {@link #MAX_TRAFFIC_VALUE}).
     *
     * @param hashCode the provided hashcode
     * @return a value in the range closed-open range, [0, {@link #MAX_TRAFFIC_VALUE})
     */
    @VisibleForTesting
    int generateBucketValue(int hashCode) {
        // map the hashCode into the range [0, BucketAlgorithm.MAX_TRAFFIC_VALUE)
        double ratio = (double) (hashCode & 0xFFFFFFFFL) / Math.pow(2, 32);
        return (int) Math.floor(MAX_TRAFFIC_VALUE * ratio);
    }


}
