package co.tinode.tinodesdk;

/**
 * A very simple thanable promise. It has no facility for execution. It can only be
 * resolved/rejected externally by calling resolve/reject. Once resolved/rejected it will call
 * a handler onSuccess/onFailure. Depending on results returned or thrown by the handler, it will
 * update the next promise in chain: will either resolve/reject it immediately, or make it
 * resolve/reject together with the promise returned by the handler.
 *
 * Usage:
 *
 * Create a PromisedReply P1, assign onSuccess/onFailure handlers with thenApply. thenApply returns
 * another P2 promise (mNextPromise), which can then be assigned its own handlers.
 *
 * The promise can be created in either WAITING or RESOLVED state by using appropriate constructor,
 *
 * The onSuccess/onFailure handlers will be called:
 *
 * a. Called at the time of resolution when P1 is resolved through P1.resolve(T) if at the time of
 * calling thenApply the promise is in WAITING state,
 * b. Called immediately on thenApply if at the time of calling thenApply the promise is already
 * in RESOLVED or REJECTED state,
 *
 * thenApply creates and returns a promise P2 which will be resolved/rejected in the following
 * manner:
 *
 * A. If P1 is resolved:
 * 1. If P1.onSuccess returns a resolved promise P3, P2 is resolved immediately on
 * return from onSuccess using the result from P3.
 * 2. If P1.onSuccess returns a rejected promise P3, P2 is rejected immediately on
 * return from onSuccess using the throwable from P3.
 * 2. If P1.onSuccess returns null, P2 is resolved immediately using result from P1.
 * 3. If P1.onSuccess returns an unresolved promise P3, P2 is resolved together with P3.
 * 4. If P1.onSuccess throws an exception, P2 is rejected immediately on catching the exception.
 * 5. If P1.onSuccess is null, P2 is resolved immediately using result from P1.
 *
 * B. If P1 is rejected:
 * 1. If P1.onFailure returns a resolved promise P3, P2 is resolved immediately on return from
 * onFailure using the result from P3.
 * 2. If P1.onFailure returns null, P2 is resolved immediately using null as a result.
 * 3. If P1.onFailure returns an unresolved promise P3, P2 is resolved together with P3.
 * 4. If P1.onFailure throws an exception, P2 is rejected immediately on catching the exception.
 * 5. If P1.onFailure is null, P2 is rejected immediately using the throwable from P1.
 * 5.1 If P2.onFailure is null, and P2.mNextPromise is null, an exception is re-thrown.
 *
 */
public class PromisedReply<T> {
    private enum State {WAITING, RESOLVED, REJECTED}

    private T mResult = null;
    private Exception mException = null;

    private volatile State mState = State.WAITING;

    private SuccessListener<T> mSuccess = null;
    private FailureListener<T> mFailure = null;

    private PromisedReply<T> mNextPromise = null;

    /**
     * Create promise in WAITING state.
     */
    public PromisedReply() {
    }

    /**
     * Create a promise in RESOLVED state
     *
     * @param result result used for resolution of the promise.
     */
    public PromisedReply(T result) {
        mResult = result;
        mState = State.RESOLVED;
    }

    /**
     * Create a promise in REJECTED state
     *
     * @param err Exception used for rejection of the promise.
     */
    public PromisedReply(Exception err) {
        mException = err;
        mState = State.REJECTED;
    }

    public PromisedReply<T> thenApply(SuccessListener<T> success, FailureListener<T> failure)
            throws Exception {
        synchronized (this) {

            if (mNextPromise != null) {
                throw new IllegalStateException("Multiple calls to thenApply are not supported");
            }

            mSuccess = success;
            mFailure = failure;
            mNextPromise = new PromisedReply<T>();
            try {
                switch (mState) {
                    case RESOLVED:
                        callOnSuccess(mResult);
                        break;

                    case REJECTED:
                        callOnFailure(mException);
                        break;

                    case WAITING:
                        break;
                }
            } catch (Exception e) {
                mNextPromise = new PromisedReply<T>(e);
            }

            return mNextPromise;
        }
    }

    private void callOnSuccess(final T result) throws Exception {
        try {
            PromisedReply<T> ret = (mSuccess != null ? mSuccess.onSuccess(result) : null);
            handleSuccess(ret);
        } catch (Exception e) {
            handleFailure(e);
        }
    }

    private void callOnFailure(final Exception err) throws Exception {
        try {
            PromisedReply<T> ret = (mFailure != null ? mFailure.onFailure(err) : null);
            handleSuccess(ret);
        } catch (Exception e) {
            handleFailure(e);
        }
    }

    private void handleSuccess(PromisedReply<T> ret) throws Exception {
        if (mNextPromise == null) {
            if (ret != null && ret.mState == State.REJECTED) {
                throw ret.mException;
            }
            return;
        }

        if (ret == null) {
            mNextPromise.resolve(mResult);
        } else if (ret.mState == State.RESOLVED) {
            mNextPromise.resolve(ret.mResult);
        } else if (ret.mState == State.REJECTED) {
            mNextPromise.reject(ret.mException);
        } else {
            // Next promise will be called when ret is completed
            ret.insertNextPromise(mNextPromise);
        }
    }

    private void handleFailure(Exception e) throws Exception {
        if (mNextPromise != null) {
            mNextPromise.reject(e);
        } else {
            throw e;
        }
    }

    public boolean isResolved() {
        return mState == State.RESOLVED;
    }

    public boolean isRejected() {
        return mState == State.REJECTED;
    }

    public boolean isDone() {
        return mState == State.RESOLVED || mState == State.REJECTED;
    }


    public void resolve(final T result) throws Exception {
        synchronized (this) {
            if (mState == State.WAITING) {
                mState = State.RESOLVED;

                mResult= result;
                callOnSuccess(result);
            } else {
                throw new IllegalStateException("Promise is already completed");
            }
        }
    }

    public void reject(final Exception err) throws Exception {
        synchronized (this) {
            if (mState == State.WAITING) {
                mState = State.REJECTED;

                mException = err;
                callOnFailure(err);
            } else {
                throw new IllegalStateException("Promise is already completed");
            }
        }
    }

    private void insertNextPromise(PromisedReply<T> next) {
        synchronized (this) {
            if (mNextPromise != null) {
                next.insertNextPromise(mNextPromise);
            }
            mNextPromise = next;
        }
    }

    public static abstract class SuccessListener<U> {
        public abstract PromisedReply<U> onSuccess(U result) throws Exception;
    }
    public static abstract class FailureListener<U> {
        public abstract PromisedReply<U> onFailure(Exception err) throws Exception;
    }
}
