package co.tinode.tinodesdk;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Attempt at a a very simple thanable promise.
 */
public class PromisedReply<T> {
    private static enum State {WAITING, DONE, CANCELLED}

    private final BlockingQueue<T> mResult = new ArrayBlockingQueue<>(1);
    private volatile State mState = State.WAITING;

    private CompletionListener<T> mListener = null;
    private PromisedReply<T> mNextToCall = null;

    private final Executor mExecutor;

    protected PromisedReply(Executor exec) {
        mExecutor = exec;
    }

    public boolean cancel(boolean mayInterruptIfRunning) {
        mState = State.CANCELLED;
        return true;
    }

    public boolean isCancelled() {
        return mState == State.CANCELLED;
    }

    public boolean isDone() {
        return mState == State.DONE;
    }

    public T get() throws InterruptedException, ExecutionException {
        return mResult.take();
    }

    public T get(long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        final T reply = mResult.poll(timeout, unit);
        if (reply == null) {
            throw new TimeoutException();
        }
        return reply;
    }

    public PromisedReply<T> thenApply(CompletionListener<T> listener) {
        if (mListener == null) {
            throw new NullPointerException();
        }

        mListener = listener;
        // Setting executor to null because the next promise will be called on this
        // mExecutor thread anyway.
        mNextToCall = new PromisedReply<T>(null);
        return mNextToCall;
    }

    private T complete(final boolean success, final T data) {
        if (mListener != null) {
            if (mExecutor != null) {
                mExecutor.submit(new Callable<T>() {
                    @Override
                    public T call() throws Exception {
                        T ret = null;
                        if (success) {
                            ret = mListener.onSuccess(data);
                        } else {
                            mListener.onFailure(data);
                        }
                        return ret;
                    }
                });
            } else {
                if (success) {
                    mListener.onSuccess(data);
                } else {
                    mListener.onFailure(data);
                }
            }
        }
    }

    private void resolver(final T result) {
        if (mListener != null) {
            try {
                T ret = mListener.onSuccess(result);
                if (mNextToCall != null) {
                    mNextToCall.resolve(ret);
                }
            } catch (Exception err) {
                if (mNextToCall != null) {
                    mNextToCall.reject(err);
                }
            }
        }
    }

    private void rejecter(final Throwable err) {
        if (mListener != null) {
            try {
                Throwable ret = mListener.onFailure(err);
                if (mNextToCall != null) {
                    mNextToCall.resolve(ret);
                }
            } catch (Exception err) {
                if (mNextToCall != null) {
                    mNextToCall.reject(err);
                }
            }
        }
    }

    protected boolean resolve(final T result) {
        if (mState == State.WAITING) {
            mState = State.DONE;
            if (mResult.offer(result)) {
                if (mExecutor != null) {
                    mExecutor.execute(new Runnable() {
                        @Override
                        public void run() {
                            resolver(result);
                        }
                    });
                } else {
                    resolver(result);
                }
                return true;
            }
        }
        return false;
    }

    protected boolean reject(final Throwable err) {
        if (mState == State.WAITING) {
            mState = State.DONE;
            complete(false, err);
            return true;
        }
        return false;
    }


    public class SuccessListener<U> {
        public U onSuccess(U result) {
            return null;
        }
    }
    public class FailureListener {
        public Throwable onFailure(Throwable err) {
            return null;
        }
    }
}
