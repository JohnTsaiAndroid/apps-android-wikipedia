package org.wikimedia.wikipedia;

import org.mediawiki.api.json.Api;
import org.mediawiki.api.json.ApiResult;
import org.wikimedia.wikipedia.concurrency.SaneAsyncTask;

import java.util.concurrent.Executor;

abstract public class ApiTask<T> extends SaneAsyncTask<T> {
    private final Api api;

    public ApiTask(Executor executor, Api api) {
        super(executor);
        this.api = api;
    }

    @Override
    public T performTask() throws Throwable {
        ApiResult result = buildRequest(api);
        return processResult(result);
    }

    abstract public ApiResult buildRequest(Api api);
    abstract public T processResult(ApiResult result) throws Throwable;

}