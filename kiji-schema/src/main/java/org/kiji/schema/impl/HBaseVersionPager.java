/**
 * (c) Copyright 2013 WibiData, Inc.
 *
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.kiji.schema.impl;

import java.io.IOException;
import java.util.NoSuchElementException;

import com.google.common.base.Preconditions;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.kiji.annotations.ApiAudience;
import org.kiji.schema.EntityId;
import org.kiji.schema.KijiColumnName;
import org.kiji.schema.KijiColumnPagingNotEnabledException;
import org.kiji.schema.KijiDataRequest;
import org.kiji.schema.KijiDataRequestBuilder;
import org.kiji.schema.KijiDataRequestBuilder.ColumnsDef;
import org.kiji.schema.KijiIOException;
import org.kiji.schema.KijiPager;
import org.kiji.schema.KijiRowData;

/**
 * Pages through the versions of a fully-qualified column.
 *
 * <p>
 *   Each page of versions is fetched by a Get RPC to the region server.
 *   The page size is translated into the Get's max-versions.
 *   The page offset is translated into the Get's max-timestamp.
 * </p>
 */
@ApiAudience.Private
public final class HBaseVersionPager implements KijiPager {
  private static final Logger LOG = LoggerFactory.getLogger(HBaseVersionPager.class);

  /** Entity ID of the row being paged through. */
  private final EntityId mEntityId;

  /** Data request template for the column being paged through. */
  private final KijiDataRequest mDataRequest;

  /** Data request details for the fully-qualified column. */
  private final KijiDataRequest.Column mColumnRequest;

  /** HBase KijiTable to read from. */
  private final HBaseKijiTable mTable;

  /** Name of the column being paged through. */
  private final KijiColumnName mColumnName;

  /** Default page size for this column. */
  private final int mDefaultPageSize;

  /** Total number of versions to return for the entire column. */
  private final int mTotalVersions;

  /** Number of versions returned so far. */
  private int mVersionsCount = 0;

  /** Max timestamp bound on the versions to fetch. */
  private long mPageMaxTimestamp;

  /** True only if there is another page of data to read through {@link #next()}. */
  private boolean mHasNext;


  /**
   * Initializes an HBaseVersionPager.
   *
   * <p>
   *   A fully-qualified column may contain a lot of cells (ie. a lot of versions).
   *   Fetching all these versions in a single Get is not always realistic.
   *   This version pager allows one to fetch subsets of the versions at a time.
   * </p>
   *
   * <p>
   *   To get a pager for a column with paging enabled,
   *   use {@link KijiRowData#getPager(String, String)}.
   * </p>
   *
   * @param entityId The entityId of the row.
   * @param dataRequest The requested data.
   * @param table The Kiji table that this row belongs to.
   * @param colName Name of the paged column.
   * @throws KijiColumnPagingNotEnabledException If paging is not enabled for the specified column.
   */
  protected HBaseVersionPager(
      EntityId entityId,
      KijiDataRequest dataRequest,
      HBaseKijiTable table,
      KijiColumnName colName)
      throws KijiColumnPagingNotEnabledException {
    Preconditions.checkArgument(colName.isFullyQualified());

    mColumnRequest = dataRequest.getColumn(colName.getFamily(), colName.getQualifier());

    if (!mColumnRequest.isPagingEnabled()) {
      throw new KijiColumnPagingNotEnabledException(
        String.format("Paging is not enabled for column '%s'.", colName));
    }

    // Construct a data request for only this column.
    final KijiDataRequestBuilder builder = KijiDataRequest.builder()
        .withTimeRange(dataRequest.getMinTimestamp(), dataRequest.getMaxTimestamp());
    builder.newColumnsDef(mColumnRequest);

    mColumnName = colName;
    mDataRequest = builder.build();
    mDefaultPageSize = mColumnRequest.getPageSize();
    mEntityId = entityId;
    mTable = table;
    mHasNext = true;  // there might be no page to read, but we don't know until we issue an RPC

    mPageMaxTimestamp = mDataRequest.getMaxTimestamp();
    mTotalVersions = mColumnRequest.getMaxVersions();
    mVersionsCount = 0;

    // Only retain the table if everything else ran fine:
    mTable.retain();
  }

  /** {@inheritDoc} */
  @Override
  public boolean hasNext() {
    return mHasNext;
  }

  /** {@inheritDoc} */
  @Override
  public KijiRowData next() {
    return next(mDefaultPageSize);
  }

  /** {@inheritDoc} */
  @Override
  public KijiRowData next(int pageSize) {
    Preconditions.checkArgument(pageSize > 0, "Page size must be >= 1, got %s", pageSize);
    if (!mHasNext) {
      throw new NoSuchElementException();
    }

    final int maxVersions = Math.min(mTotalVersions - mVersionsCount, pageSize);

    // Clone the column data request template, but adjust the max-timestamp and the max-versions:
    final KijiDataRequest nextPageDataRequest = KijiDataRequest.builder()
        .withTimeRange(mDataRequest.getMinTimestamp(), mPageMaxTimestamp)
        .addColumns(ColumnsDef.create()
            .withFilter(mColumnRequest.getFilter())
            .withMaxVersions(maxVersions)
            .add(mColumnName))
        .build();

    final HBaseDataRequestAdapter adapter = new HBaseDataRequestAdapter(nextPageDataRequest);
    try {
      final Get hbaseGet = adapter.toGet(mEntityId, mTable.getLayout());
      LOG.debug("Sending HBase Get: {}", hbaseGet);
      final Result result = mTable.getHTable().get(hbaseGet);
      LOG.debug("{} cells were requested, {} cells were received.", pageSize, result.size());

      if (result.size() < maxVersions) {
        // We got fewer versions than the number we expected, that means there are no more
        // versions to page through:
        mHasNext = false;
      } else {
        // track how far we have gone:
        final KeyValue last = result.raw()[result.raw().length - 1];
        mPageMaxTimestamp = last.getTimestamp();  // max-timestamp is exclusive
        mVersionsCount += result.raw().length;

        if ((mPageMaxTimestamp <= mDataRequest.getMinTimestamp())
            || (mVersionsCount >= mTotalVersions)) {
          mHasNext = false;
        }
      }

      return new HBaseKijiRowData(mEntityId, nextPageDataRequest, mTable, result);
    } catch (IOException ioe) {
      throw new KijiIOException(ioe);
    }
  }

  /** {@inheritDoc} */
  @Override
  public void remove() {
    throw new UnsupportedOperationException("KijiPager.remove() is not supported.");
  }

  /** {@inheritDoc} */
  @Override
  public void close() throws IOException {
    // TODO: Ensure that close() has been invoked properly (through finalize()).
    mTable.release();
  }
}
