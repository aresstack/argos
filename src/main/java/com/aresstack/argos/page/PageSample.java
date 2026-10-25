package com.aresstack.argos.page;

/**
 * One observation of the page taken from outside its JavaScript world.
 *
 * @param elementCount     number of elements in the document (DOM fingerprint)
 * @param textLength       length of the visible text (DOM fingerprint and content check)
 * @param loadingVisible   a configured loading indicator is visible
 * @param targetPresent    the awaited selector/text is visible (true when nothing is awaited)
 * @param inflightRequests requests started but not yet finished or failed
 * @param quietMillis      time since the last request started, finished or failed
 */
public record PageSample(int elementCount, int textLength, boolean loadingVisible, boolean targetPresent,
                         int inflightRequests, long quietMillis) {

    boolean sameDomAs(PageSample other) {
        return other != null && elementCount == other.elementCount && textLength == other.textLength;
    }
}
