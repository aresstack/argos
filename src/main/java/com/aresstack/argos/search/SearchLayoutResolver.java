package com.aresstack.argos.search;

import java.util.List;
import java.util.Optional;

/**
 * Optional AI port for search pages the mechanical analysis did not understand (AskAI's
 * AiSearchPageLayoutResolver contract, reduced to its essence). It is consulted only on
 * LOW_CONFIDENCE. It may only choose among the offered container ids; its decision is validated and
 * then fed into the same block extraction as the mechanical path. Argos ships without a model, so the
 * default is {@link #NONE} and LOW_CONFIDENCE stays EXTRACTION_FAILED.
 */
public interface SearchLayoutResolver {
    SearchLayoutResolver NONE = request -> Optional.empty();

    Optional<Decision> resolve(Request request);

    /** One candidate region as offered to the model: id plus a one-line description. */
    record Candidate(String containerId, String description) {
    }

    record Request(String query, String pageUrl, String snapshotId, List<Candidate> candidates) {
        public Request {
            candidates = List.copyOf(candidates);
        }
    }

    /** The organic region and, optionally, its result block ids. Ids must come from the request. */
    record Decision(String snapshotId, String organicContainerId, List<String> resultBlockIds, double confidence) {
        public Decision {
            resultBlockIds = List.copyOf(resultBlockIds);
        }
    }
}
