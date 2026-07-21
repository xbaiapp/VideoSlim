package com.videoslim.videoslim;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A read-only view of an owned completion stage.
 *
 * <p>All CompletionStage methods delegated here are part of Android API 24. The authoritative stage
 * remains private, and each call to {@link #toCompletableFuture()} returns an independently mutable
 * mirror.
 */
final class ObservationalCompletionStage<T> implements CompletionStage<T> {
    private final CompletionStage<T> source;

    ObservationalCompletionStage(CompletionStage<T> source) {
        this.source = source;
    }

    @Override
    public <U> CompletionStage<U> thenApply(Function<? super T, ? extends U> function) {
        return source.thenApply(function);
    }

    @Override
    public <U> CompletionStage<U> thenApplyAsync(Function<? super T, ? extends U> function) {
        return source.thenApplyAsync(function);
    }

    @Override
    public <U> CompletionStage<U> thenApplyAsync(
            Function<? super T, ? extends U> function, Executor executor) {
        return source.thenApplyAsync(function, executor);
    }

    @Override
    public CompletionStage<Void> thenAccept(Consumer<? super T> action) {
        return source.thenAccept(action);
    }

    @Override
    public CompletionStage<Void> thenAcceptAsync(Consumer<? super T> action) {
        return source.thenAcceptAsync(action);
    }

    @Override
    public CompletionStage<Void> thenAcceptAsync(Consumer<? super T> action, Executor executor) {
        return source.thenAcceptAsync(action, executor);
    }

    @Override
    public CompletionStage<Void> thenRun(Runnable action) {
        return source.thenRun(action);
    }

    @Override
    public CompletionStage<Void> thenRunAsync(Runnable action) {
        return source.thenRunAsync(action);
    }

    @Override
    public CompletionStage<Void> thenRunAsync(Runnable action, Executor executor) {
        return source.thenRunAsync(action, executor);
    }

    @Override
    public <U, V> CompletionStage<V> thenCombine(
            CompletionStage<? extends U> other,
            BiFunction<? super T, ? super U, ? extends V> function) {
        return source.thenCombine(other, function);
    }

    @Override
    public <U, V> CompletionStage<V> thenCombineAsync(
            CompletionStage<? extends U> other,
            BiFunction<? super T, ? super U, ? extends V> function) {
        return source.thenCombineAsync(other, function);
    }

    @Override
    public <U, V> CompletionStage<V> thenCombineAsync(
            CompletionStage<? extends U> other,
            BiFunction<? super T, ? super U, ? extends V> function,
            Executor executor) {
        return source.thenCombineAsync(other, function, executor);
    }

    @Override
    public <U> CompletionStage<Void> thenAcceptBoth(
            CompletionStage<? extends U> other, BiConsumer<? super T, ? super U> action) {
        return source.thenAcceptBoth(other, action);
    }

    @Override
    public <U> CompletionStage<Void> thenAcceptBothAsync(
            CompletionStage<? extends U> other, BiConsumer<? super T, ? super U> action) {
        return source.thenAcceptBothAsync(other, action);
    }

    @Override
    public <U> CompletionStage<Void> thenAcceptBothAsync(
            CompletionStage<? extends U> other,
            BiConsumer<? super T, ? super U> action,
            Executor executor) {
        return source.thenAcceptBothAsync(other, action, executor);
    }

    @Override
    public CompletionStage<Void> runAfterBoth(CompletionStage<?> other, Runnable action) {
        return source.runAfterBoth(other, action);
    }

    @Override
    public CompletionStage<Void> runAfterBothAsync(CompletionStage<?> other, Runnable action) {
        return source.runAfterBothAsync(other, action);
    }

    @Override
    public CompletionStage<Void> runAfterBothAsync(
            CompletionStage<?> other, Runnable action, Executor executor) {
        return source.runAfterBothAsync(other, action, executor);
    }

    @Override
    public <U> CompletionStage<U> applyToEither(
            CompletionStage<? extends T> other, Function<? super T, U> function) {
        return source.applyToEither(other, function);
    }

    @Override
    public <U> CompletionStage<U> applyToEitherAsync(
            CompletionStage<? extends T> other, Function<? super T, U> function) {
        return source.applyToEitherAsync(other, function);
    }

    @Override
    public <U> CompletionStage<U> applyToEitherAsync(
            CompletionStage<? extends T> other,
            Function<? super T, U> function,
            Executor executor) {
        return source.applyToEitherAsync(other, function, executor);
    }

    @Override
    public CompletionStage<Void> acceptEither(
            CompletionStage<? extends T> other, Consumer<? super T> action) {
        return source.acceptEither(other, action);
    }

    @Override
    public CompletionStage<Void> acceptEitherAsync(
            CompletionStage<? extends T> other, Consumer<? super T> action) {
        return source.acceptEitherAsync(other, action);
    }

    @Override
    public CompletionStage<Void> acceptEitherAsync(
            CompletionStage<? extends T> other, Consumer<? super T> action, Executor executor) {
        return source.acceptEitherAsync(other, action, executor);
    }

    @Override
    public CompletionStage<Void> runAfterEither(CompletionStage<?> other, Runnable action) {
        return source.runAfterEither(other, action);
    }

    @Override
    public CompletionStage<Void> runAfterEitherAsync(CompletionStage<?> other, Runnable action) {
        return source.runAfterEitherAsync(other, action);
    }

    @Override
    public CompletionStage<Void> runAfterEitherAsync(
            CompletionStage<?> other, Runnable action, Executor executor) {
        return source.runAfterEitherAsync(other, action, executor);
    }

    @Override
    public <U> CompletionStage<U> thenCompose(
            Function<? super T, ? extends CompletionStage<U>> function) {
        return source.thenCompose(function);
    }

    @Override
    public <U> CompletionStage<U> thenComposeAsync(
            Function<? super T, ? extends CompletionStage<U>> function) {
        return source.thenComposeAsync(function);
    }

    @Override
    public <U> CompletionStage<U> thenComposeAsync(
            Function<? super T, ? extends CompletionStage<U>> function, Executor executor) {
        return source.thenComposeAsync(function, executor);
    }

    @Override
    public <U> CompletionStage<U> handle(
            BiFunction<? super T, Throwable, ? extends U> function) {
        return source.handle(function);
    }

    @Override
    public <U> CompletionStage<U> handleAsync(
            BiFunction<? super T, Throwable, ? extends U> function) {
        return source.handleAsync(function);
    }

    @Override
    public <U> CompletionStage<U> handleAsync(
            BiFunction<? super T, Throwable, ? extends U> function, Executor executor) {
        return source.handleAsync(function, executor);
    }

    @Override
    public CompletionStage<T> whenComplete(BiConsumer<? super T, ? super Throwable> action) {
        return source.whenComplete(action);
    }

    @Override
    public CompletionStage<T> whenCompleteAsync(BiConsumer<? super T, ? super Throwable> action) {
        return source.whenCompleteAsync(action);
    }

    @Override
    public CompletionStage<T> whenCompleteAsync(
            BiConsumer<? super T, ? super Throwable> action, Executor executor) {
        return source.whenCompleteAsync(action, executor);
    }

    @Override
    public CompletionStage<T> exceptionally(Function<Throwable, ? extends T> function) {
        return source.exceptionally(function);
    }

    @Override
    public CompletableFuture<T> toCompletableFuture() {
        final CompletableFuture<T> mirror = new CompletableFuture<>();
        source.whenComplete(
                new BiConsumer<T, Throwable>() {
                    @Override
                    public void accept(T value, Throwable error) {
                        if (error == null) {
                            mirror.complete(value);
                        } else {
                            mirror.completeExceptionally(error);
                        }
                    }
                });
        return mirror;
    }
}
