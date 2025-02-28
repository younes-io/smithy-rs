/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

use crate::body::BoxBody;
use crate::extension::RuntimeErrorExtension;
use crate::proto::rest::router::Error;
use crate::response::IntoResponse;
use crate::routers::{method_disallowed, UNKNOWN_OPERATION_EXCEPTION};

use super::RestJson1;

pub use crate::proto::rest::router::*;

impl IntoResponse<RestJson1> for Error {
    fn into_response(self) -> http::Response<BoxBody> {
        match self {
            Error::NotFound => http::Response::builder()
                .status(http::StatusCode::NOT_FOUND)
                .header(http::header::CONTENT_TYPE, "application/json")
                .header("X-Amzn-Errortype", UNKNOWN_OPERATION_EXCEPTION)
                .extension(RuntimeErrorExtension::new(
                    UNKNOWN_OPERATION_EXCEPTION.to_string(),
                ))
                .body(crate::body::to_boxed("{}"))
                .expect("invalid HTTP response for REST JSON routing error; please file a bug report under https://github.com/awslabs/smithy-rs/issues"),
            Error::MethodNotAllowed => method_disallowed(),
        }
    }
}
