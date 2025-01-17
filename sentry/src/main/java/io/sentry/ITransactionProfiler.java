package io.sentry;

import java.util.List;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Used for performing operations when a transaction is started or ended. */
@ApiStatus.Internal
public interface ITransactionProfiler {
  void onTransactionStart(@NotNull ITransaction transaction);

  @Nullable
  ProfilingTraceData onTransactionFinish(
      @NotNull ITransaction transaction,
      @Nullable List<PerformanceCollectionData> performanceCollectionData);

  /** Cancel the profiler and stops it. Used on SDK close. */
  void close();
}
