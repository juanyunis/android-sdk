package co.tinode.tinodesdk;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A very simple thanable promise.
 */
public class PromisedReply<T> {
    private enum State {WAITING, RESOLVED, REJECTED, CANCELLED}

    private final BlockingQueue<T> mResult = new ArrayBlockingQueue<>(1);
    private volatile State mState = State.WAITING;

    private SuccessListener<T> mSuccess = null;
    private FailureListener<T> mFailure = null;

    private PromisedReply<T> mNextPromise = null;

    private final Executor mExecutor;

    protected PromisedReply(Executor exec) {
        mExecutor = exec;
    }

    protected PromisedReply(Executor exec, T result) {
        mExecutor = exec;
        mResult.add(result);
        mState = State.RESOLVED;
    }

    public PromisedReply<T> thenApply(SuccessListener<T> success, FailureListener failure) {
        mSuccess = success;
        mFailure = failure;

        mNextPromise = new PromisedReply<T>(mExecutor);
        return mNextPromise;
    }

    private void resolver(final T result) {
        if (mSuccess != null) {
            try {
                PromisedReply<T> ret = mSuccess.onSuccess(result);
                insertNextPromise(ret);
            } catch (Exception err) {
                if (mNextPromise != null) {
                    mNextPromise.reject(err);
                }
            }
        }
    }

    private void rejecter(final Throwable err) {
        if (mFailure != null) {
            try {
                PromisedReply<T> ret = mFailure.onFailure(err);
                insertNextPromise(ret);
            } catch (Exception ex) {
                if (mNextPromise != null) {
                    mNextPromise.reject(ex);
                }
            }
        }
    }

    protected boolean resolve(final T result) {
        synchronized (this) {
            if (mState == State.WAITING) {
                mState = State.RESOLVED;

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
        }
        return false;
    }

    protected boolean reject(final Throwable err) {
        synchronized (this) {
            if (mState == State.WAITING) {
                mState = State.REJECTED;
                if (mExecutor != null) {
                    mExecutor.execute(new Runnable() {
                        @Override
                        public void run() {
                            rejecter(err);
                        }
                    });
                } else {
                    rejecter(err);
                }
                return true;
            }
        }
        return false;
    }

    protected void insertNextPromise(PromisedReply<T> next) {
        synchronized (this) {
            if (mNextPromise != null) {
                next.insertNextPromise(mNextPromise);
            }
            mNextPromise = next;
        }
    }

    public static abstract class SuccessListener<U> {
        public abstract PromisedReply<U> onSuccess(U result);
    }
    public static abstract class FailureListener<U> {
        public abstract PromisedReply<U> onFailure(Throwable err);
    }
}
