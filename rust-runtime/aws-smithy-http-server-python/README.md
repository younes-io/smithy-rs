# aws-smithy-http-server-python

Server libraries for smithy-rs generated servers, targeting pure Python business logic.

## Running servers on AWS Lambda

`aws-smithy-http-server-python` supports running your services on [AWS Lambda](https://aws.amazon.com/lambda/).

You need to use `run_lambda` method instead of `run` method to start
the [custom runtime](https://docs.aws.amazon.com/lambda/latest/dg/runtimes-custom.html)
instead of the [Hyper](https://hyper.rs/) HTTP server.

In your `app.py`:

```diff
from libpokemon_service_server_sdk import App
from libpokemon_service_server_sdk.error import ResourceNotFoundException

# ...

# Get the number of requests served by this server.
@app.get_server_statistics
def get_server_statistics(
    _: GetServerStatisticsInput, context: Context
) -> GetServerStatisticsOutput:
    calls_count = context.get_calls_count()
    logging.debug("The service handled %d requests", calls_count)
    return GetServerStatisticsOutput(calls_count=calls_count)

# ...

-app.run()
+app.run_lambda()
```

`aws-smithy-http-server-python` comes with a
[custom runtime](https://docs.aws.amazon.com/lambda/latest/dg/runtimes-custom.html)
so you should run your service without any provided runtimes.
You can achieve that with a `Dockerfile` similar to this:

```dockerfile
# You can use any image that has your desired Python version
FROM public.ecr.aws/lambda/python:3.8-x86_64

# Copy your application code to `LAMBDA_TASK_ROOT`
COPY app.py ${LAMBDA_TASK_ROOT}
# When you build your Server SDK for your service you get a shared library
# that is importable in Python. You need to copy that shared library to same folder
# with your application code, so it can be imported by your application.
# Note that you need to build your library for Linux,
# if you are on a different platform you can consult to
# https://pyo3.rs/latest/building_and_distribution.html#cross-compiling
# for cross compiling.
COPY lib_pokemon_service_server_sdk.so ${LAMBDA_TASK_ROOT}

# You can install your application's dependencies using file `requirements.txt`
# from your project folder, if you have any.
# COPY requirements.txt  .
# RUN  pip3 install -r requirements.txt --target "${LAMBDA_TASK_ROOT}"

# Create a symlink for your application's entrypoint,
# so we can use `/app.py` to refer it
RUN ln -s ${LAMBDA_TASK_ROOT}/app.py /app.py

# By default `public.ecr.aws/lambda/python` images comes with Python runtime,
# we need to override `ENTRYPOINT` and `CMD` to not call that runtime and
# instead run directly your service and it will start our custom runtime.
ENTRYPOINT [ "/var/lang/bin/python3.8" ]
CMD [ "/app.py" ]
```

<!-- anchor_start:footer -->
This crate is part of the [AWS SDK for Rust](https://awslabs.github.io/aws-sdk-rust/) and the [smithy-rs](https://github.com/awslabs/smithy-rs) code generator. In most cases, it should not be used directly.
<!-- anchor_end:footer -->
