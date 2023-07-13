package org.ta4j.core.indicators.caching;

import java.util.*;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ta4j.core.BarSeries;

public class NativeIndicatorValueCache<T> implements IndicatorValueCache<T>{

    private static final Logger log = LoggerFactory.getLogger(NativeIndicatorValueCache.class);

    /** List of cached results. */
    private final List<T> results;

    /**
     * Should always be the index of the last (calculated) result in
     * {@link #results}.
     */
    protected int highestResultIndex = -1;

    public NativeIndicatorValueCache(IndicatorValueCacheConfig indicatorValueCacheConfig) {
        int limit = indicatorValueCacheConfig.getMaximumSize();
        this.results = limit == Integer.MAX_VALUE ? new ArrayList<>() : new ArrayList<>(limit);
    }

    @Override
    public T get(CacheKeyHolder keyHolder, Function<CacheKeyHolder, T> mappingFunction) {
        BarSeries series = keyHolder.getBarSeries();
        int index = keyHolder.getIndex();
        if (series == null) {
            // Series is null; the indicator doesn't need cache.
            // (e.g. simple computation of the value)
            // --> Calculating the value
            T result = mappingFunction.apply(keyHolder);
            if (log.isTraceEnabled()) {
                log.info("{}({}): {}", this, index, result);
            }
            return result;
        }

        // Series is not null

        final int removedBarsCount = series.getRemovedBarsCount();
        final int maximumResultCount = series.getMaximumBarCount();

        T result;
        if (index < removedBarsCount) {
            // Result already removed from cache
            if (log.isTraceEnabled()) {
                log.info("{}: result from bar {} already removed from cache, use {}-th instead",
                        getClass().getSimpleName(), index, removedBarsCount);
            }
            increaseLengthTo(removedBarsCount, maximumResultCount);
            highestResultIndex = removedBarsCount;
            result = results.get(0);
            if (result == null) {
                // It should be "result = calculate(removedBarsCount);".
                // We use "result = calculate(0);" as a workaround
                // to fix issue #120 (https://github.com/mdeverdelhan/ta4j/issues/120).
                result = mappingFunction.apply(BaseCacheKeyHolder.of(0, keyHolder.getBar(), keyHolder.getBarSeries()));
                results.set(0, result);
            }
        } else {
            if (index == series.getEndIndex()) {
                // Don't cache result if last bar
                result = mappingFunction.apply(keyHolder);
            } else {
                increaseLengthTo(index, maximumResultCount);
                if (index > highestResultIndex) {
                    // Result not calculated yet
                    highestResultIndex = index;
                    result = mappingFunction.apply(keyHolder);
                    results.set(results.size() - 1, result);
                } else {
                    // Result covered by current cache
                    int resultInnerIndex = results.size() - 1 - (highestResultIndex - index);
                    result = results.get(resultInnerIndex);
                    if (result == null) {
                        result = mappingFunction.apply(keyHolder);
                        results.set(resultInnerIndex, result);
                    }
                }
            }

        }
        if (log.isTraceEnabled()) {
            log.info("{}({}): {}", this, index, result);
        }
        return result;
    }

    @Override
    public void put(CacheKeyHolder key, T result) {
        throw new UnsupportedOperationException("Cannot manually put values in a native cache");
    }

    @Override
    public Map<Object, T> getValues() {
        Map<Object, T> resultMap = new HashMap<>();
        for(int i = 0; i < results.size(); i++) {
            resultMap.put(i, results.get(i));
        }
        return resultMap;
    }

    @Override
    public void clear() {

    }

    /**
     * Increases the size of the cached results buffer.
     *
     * @param index     the index to increase length to
     * @param maxLength the maximum length of the results buffer
     */
    private void increaseLengthTo(int index, int maxLength) {
        if (highestResultIndex > -1) {
            int newResultsCount = Math.min(index - highestResultIndex, maxLength);
            if (newResultsCount == maxLength) {
                results.clear();
                results.addAll(Collections.nCopies(maxLength, null));
            } else if (newResultsCount > 0) {
                results.addAll(Collections.nCopies(newResultsCount, null));
                removeExceedingResults(maxLength);
            }
        } else {
            // First use of cache
            assert results.isEmpty() : "Cache results list should be empty";
            results.addAll(Collections.nCopies(Math.min(index + 1, maxLength), null));
        }
    }

    /**
     * Removes the N first results which exceed the maximum bar count. (i.e. keeps
     * only the last maximumResultCount results)
     *
     * @param maximumResultCount the number of results to keep
     */
    private void removeExceedingResults(int maximumResultCount) {
        int resultCount = results.size();
        if (resultCount > maximumResultCount) {
            // Removing old results
            final int nbResultsToRemove = resultCount - maximumResultCount;
            if (nbResultsToRemove == 1) {
                results.remove(0);
            } else {
                results.subList(0, nbResultsToRemove).clear();
            }
        }
    }
}