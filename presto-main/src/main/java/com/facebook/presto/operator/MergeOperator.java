/*
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
package com.facebook.presto.operator;

import com.facebook.presto.connector.ConnectorId;
import com.facebook.presto.execution.SystemMemoryUsageListener;
import com.facebook.presto.execution.buffer.PagesSerde;
import com.facebook.presto.execution.buffer.PagesSerdeFactory;
import com.facebook.presto.execution.buffer.SerializedPage;
import com.facebook.presto.metadata.Split;
import com.facebook.presto.spi.Page;
import com.facebook.presto.spi.PageBuilder;
import com.facebook.presto.spi.UpdatablePageSource;
import com.facebook.presto.spi.block.Block;
import com.facebook.presto.spi.block.SortOrder;
import com.facebook.presto.spi.type.Type;
import com.facebook.presto.split.RemoteSplit;
import com.facebook.presto.sql.planner.plan.PlanNodeId;
import com.google.common.collect.ImmutableList;
import com.google.common.io.Closer;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;

import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;

import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

public class MergeOperator
        implements SourceOperator, Closeable
{
    public static final ConnectorId REMOTE_CONNECTOR_ID = new ConnectorId("$remote");

    public static class MergeOperatorFactory
            implements SourceOperatorFactory
    {
        private final int operatorId;
        private final PlanNodeId sourceId;
        private final ExchangeClientSupplier exchangeClientSupplier;
        private final PagesSerdeFactory serdeFactory;
        private final List<Type> sourceTypes;
        private final List<Integer> outputChannels;
        private final List<Type> outputTypes;
        private final List<Integer> sortChannels;
        private final List<SortOrder> sortOrder;
        private final MergeSortComparatorFactory mergeSortComparatorFactory;
        private boolean closed;

        public MergeOperatorFactory(
                int operatorId,
                PlanNodeId sourceId,
                ExchangeClientSupplier exchangeClientSupplier,
                PagesSerdeFactory serdeFactory,
                MergeSortComparatorFactory mergeSortComparatorFactory,
                List<Type> sourceTypes,
                List<Integer> outputChannels,
                List<Integer> sortChannels,
                List<SortOrder> sortOrder)
        {
            this.operatorId = operatorId;
            this.sourceId = requireNonNull(sourceId, "sourceId is null");
            this.exchangeClientSupplier = requireNonNull(exchangeClientSupplier, "exchangeClientSupplier is null");
            this.serdeFactory = requireNonNull(serdeFactory, "serdeFactory is null");
            this.sourceTypes = requireNonNull(sourceTypes, "sourceTypes is null");
            this.outputChannels = requireNonNull(outputChannels, "outputChannels is null");
            this.outputTypes = outputTypes(sourceTypes, outputChannels);
            this.sortChannels = requireNonNull(sortChannels, "sortChannels is null");
            this.sortOrder = requireNonNull(sortOrder, "sortOrder is null");
            this.mergeSortComparatorFactory = requireNonNull(mergeSortComparatorFactory, "mergeSortComparatorFactory is null");
        }

        private static List<Type> outputTypes(List<? extends Type> sourceTypes, List<Integer> outputChannels)
        {
            ImmutableList.Builder<Type> types = ImmutableList.builder();
            for (int channel : outputChannels) {
                types.add(sourceTypes.get(channel));
            }
            return types.build();
        }

        private static List<Type> sortTypes(List<? extends Type> sourceTypes, List<Integer> sortChannels)
        {
            ImmutableList.Builder<Type> types = ImmutableList.builder();
            for (int channel : sortChannels) {
                types.add(sourceTypes.get(channel));
            }
            return types.build();
        }

        @Override
        public PlanNodeId getSourceId()
        {
            return sourceId;
        }

        @Override
        public List<Type> getTypes()
        {
            return outputTypes;
        }

        @Override
        public SourceOperator createOperator(DriverContext driverContext)
        {
            checkState(!closed, "Factory is already closed");
            OperatorContext operatorContext = driverContext.addOperatorContext(operatorId, sourceId, MergeOperator.class.getSimpleName());

            return new MergeOperator(
                    operatorContext,
                    sourceId,
                    () -> exchangeClientSupplier.get(new UpdateSystemMemory(driverContext.getPipelineContext())),
                    serdeFactory.createPagesSerde(),
                    mergeSortComparatorFactory.create(sortTypes(sourceTypes, sortChannels), sortChannels, sortOrder),
                    outputChannels,
                    outputTypes);
        }

        @Override
        public void close()
        {
            closed = true;
        }
    }

    @NotThreadSafe
    private static final class UpdateSystemMemory
            implements SystemMemoryUsageListener
    {
        private final PipelineContext pipelineContext;

        public UpdateSystemMemory(PipelineContext pipelineContext)
        {
            this.pipelineContext = requireNonNull(pipelineContext, "pipelineContext is null");
        }

        @Override
        public void updateSystemMemoryUsage(long deltaMemoryInBytes)
        {
            if (deltaMemoryInBytes > 0) {
                pipelineContext.reserveSystemMemory(deltaMemoryInBytes);
            }
            else {
                pipelineContext.freeSystemMemory(-deltaMemoryInBytes);
            }
        }
    }

    private final OperatorContext operatorContext;
    private final PlanNodeId sourceId;
    private final Supplier<ExchangeClient> exchangeClientSupplier;
    private final PagesSerde pagesSerde;
    private final MergeSortComparator comparator;
    private final List<Integer> outputChannels;
    private final List<Type> outputTypes;
    private final PageBuilder pageBuilder;

    private final SettableFuture<Void> blockedOnSplits = SettableFuture.create();

    private final Set<URI> locations = new HashSet<>();
    private final Closer closer = Closer.create();
    private MergeSources mergeSources;

    private boolean closed;

    public MergeOperator(
            OperatorContext operatorContext,
            PlanNodeId sourceId,
            Supplier<ExchangeClient> exchangeClientSupplier,
            PagesSerde pagesSerde,
            MergeSortComparator comparator,
            List<Integer> outputChannels,
            List<Type> outputTypes)
    {
        this.operatorContext = requireNonNull(operatorContext, "operatorContext is null");
        this.sourceId = requireNonNull(sourceId, "sourceId is null");
        this.exchangeClientSupplier = requireNonNull(exchangeClientSupplier, "exchangeClientSupplier is null");
        this.pagesSerde = requireNonNull(pagesSerde, "pagesSerde is null");
        this.comparator = requireNonNull(comparator, "comparator is null");
        this.outputChannels = requireNonNull(outputChannels, "outputChannels is null");
        this.outputTypes = requireNonNull(outputTypes, "outputTypes is null");
        this.pageBuilder = new PageBuilder(outputTypes);
    }

    @Override
    public PlanNodeId getSourceId()
    {
        return sourceId;
    }

    @Override
    public Supplier<Optional<UpdatablePageSource>> addSplit(Split split)
    {
        requireNonNull(split, "split is null");
        checkArgument(split.getConnectorId().equals(REMOTE_CONNECTOR_ID), "split is not a remote split");

        URI location = ((RemoteSplit) split.getConnectorSplit()).getLocation();
        locations.add(location);
        return Optional::empty;
    }

    @Override
    public void noMoreSplits()
    {
        ImmutableList.Builder<MergeSource> builder = ImmutableList.builder();
        for (URI location : locations) {
            ExchangeClient exchangeClient = closer.register(exchangeClientSupplier.get());
            exchangeClient.addLocation(location);
            exchangeClient.noMoreLocations();
            builder.add(new MergeSource(location, exchangeClient, pagesSerde));
        }

        mergeSources = new MergeSources(builder.build());
        blockedOnSplits.set(null);
    }

    @Override
    public OperatorContext getOperatorContext()
    {
        return operatorContext;
    }

    @Override
    public List<Type> getTypes()
    {
        return outputTypes;
    }

    @Override
    public void finish()
    {
        close();
    }

    @Override
    public boolean isFinished()
    {
        return closed || (mergeSources != null && mergeSources.isFinished() && pageBuilder.isEmpty());
    }

    @Override
    public ListenableFuture<?> isBlocked()
    {
        if (!blockedOnSplits.isDone()) {
            return blockedOnSplits;
        }
        return mergeSources.isBlocked();
    }

    @Override
    public boolean needsInput()
    {
        return false;
    }

    @Override
    public void addInput(Page page)
    {
        throw new UnsupportedOperationException(getClass().getName() + " can not take input");
    }

    @Override
    public Page getOutput()
    {
        checkState(mergeSources != null, "mergeSources must be non-null when getting input");

        if (closed) {
            return null;
        }

        while (!pageBuilder.isFull()) {
            PageWithPosition pageWithPosition = selectTopValue(mergeSources.getMergeSources());
            if (pageWithPosition == null) {
                break;
            }

            Page page = pageWithPosition.getPage();
            int position = pageWithPosition.getPosition();

            // append the row
            pageBuilder.declarePosition();
            for (int i = 0; i < outputChannels.size(); i++) {
                Type type = outputTypes.get(i);
                Block block = page.getBlock(outputChannels.get(i));
                type.appendTo(block, position, pageBuilder.getBlockBuilder(i));
            }

            pageWithPosition.incrementPosition();
        }

        if (pageBuilder.isEmpty()) {
            return null;
        }

        // As in LookupJoinOperator, only flush full pages unless we are done
        if (pageBuilder.isFull() || mergeSources.isFinished()) {
            Page page = pageBuilder.build();
            operatorContext.recordGeneratedInput(page.getSizeInBytes(), page.getPositionCount());
            pageBuilder.reset();
            return page;
        }

        return null;
    }

    @Nullable
    private PageWithPosition selectTopValue(List<MergeSource> mergeSources)
    {
        checkArgument(!mergeSources.isEmpty());
        PageWithPosition result = null;
        for (MergeSource mergeSource : mergeSources) {
            PageWithPosition current = mergeSource.getPage();
            if (current == null) {
                if (mergeSource.isFinished()) {
                    continue;
                }
                else {
                    return null;
                }
            }
            if (result == null) {
                result = current;
            }
            else {
                int compareResult = comparator.compareTo(current.getPage(), current.getPosition(), result.getPage(), result.getPosition());
                if (compareResult < 0) {
                    result = current;
                }
            }
        }
        return result;
    }

    @Override
    public void close()
    {
        try {
            closed = true;
            closer.close();
        }
        catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static class MergeSources
    {
        private final List<MergeSource> mergeSources;
        private ListenableFuture<?> blocked;

        public MergeSources(List<MergeSource> mergeSources)
        {
            this.mergeSources = ImmutableList.copyOf(mergeSources);
        }

        public ListenableFuture<?> isBlocked()
        {
            if (mergeSources.stream().allMatch(source -> source.isBlocked().isDone())) {
                return NOT_BLOCKED;
            }
            if (blocked != null && !blocked.isDone()) {
                return blocked;
            }
            blocked = Futures.allAsList(mergeSources.stream().map(MergeSource::isBlocked).collect(Collectors.toList()));
            return blocked;
        }

        public boolean isFinished()
        {
            return mergeSources.stream().allMatch(MergeSource::isFinished);
        }

        public List<MergeSource> getMergeSources()
        {
            return mergeSources;
        }
    }

    private static class MergeSource
    {
        private final URI location;
        private final ExchangeClient exchangeClient;
        private final PagesSerde serde;

        private PageWithPosition currentPage;
        private ListenableFuture<?> blocked;

        public MergeSource(URI location, ExchangeClient exchangeClient, PagesSerde serde)
        {
            this.location = requireNonNull(location, "location is null");
            this.exchangeClient = requireNonNull(exchangeClient, "exchangeClient is null");
            this.serde = requireNonNull(serde, "serde is null");
        }

        public URI getLocation()
        {
            return location;
        }

        public ListenableFuture<?> isBlocked()
        {
            if (currentPage != null && !currentPage.isFinished()) {
                return NOT_BLOCKED;
            }
            if (blocked != null && !blocked.isDone()) {
                return blocked;
            }
            blocked = exchangeClient.isBlocked();
            return blocked;
        }

        public boolean isFinished()
        {
            return exchangeClient.isFinished() && (currentPage == null || currentPage.isFinished());
        }

        @Nullable
        public PageWithPosition getPage()
        {
            if (currentPage != null && !currentPage.isFinished()) {
                return currentPage;
            }

            checkState(blocked != null, "blocked is null");
            if (!isBlocked().isDone()) {
                return null;
            }

            if (exchangeClient.isFinished()) {
                return null;
            }

            SerializedPage serializedPage = exchangeClient.pollPage();
            checkState(serializedPage != null, "exchange client has returned null for the next page");
            Page page = serde.deserialize(serializedPage);
            currentPage = new PageWithPosition(page);
            return currentPage;
        }
    }

    private static class PageWithPosition
    {
        private final Page page;
        private int position = 0;

        public PageWithPosition(Page page)
        {
            this.page = requireNonNull(page, "page is null");
        }

        public Page getPage()
        {
            return page;
        }

        public int getPosition()
        {
            checkPosition();
            return position;
        }

        public void incrementPosition()
        {
            checkPosition();
            position++;
        }

        public boolean isFinished()
        {
            return position == page.getPositionCount();
        }

        private void checkPosition()
        {
            int positionCount = page.getPositionCount();
            checkState(position < positionCount, "Invalid position: %d of %d", position, positionCount);
        }
    }
}
