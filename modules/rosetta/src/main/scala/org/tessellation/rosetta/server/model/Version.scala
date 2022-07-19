/**
  * Rosetta
  * Build Once. Integrate Your Blockchain Everywhere.
  *
  * The version of the OpenAPI document: 1.4.12
  * Contact: team@openapitools.org
  *
  * NOTE: This class is auto generated by OpenAPI Generator (https://openapi-generator.tech).
  * https://openapi-generator.tech
  */

package org.tessellation.rosetta.server.model
import org.tessellation.rosetta.server.model.dag.schema.GenericMetadata

case class Version(
  /* The rosetta_version is the version of the Rosetta interface the implementation adheres to. This can be useful for clients looking to reliably parse responses. */
  rosettaVersion: String,
  /* The node_version is the canonical version of the node runtime. This can help clients manage deployments. */
  nodeVersion: String,
  /* When a middleware org.tessellation.server is used to adhere to the Rosetta interface, it should return its version here. This can help clients manage deployments. */
  middlewareVersion: Option[String],
  /* Any other information that may be useful about versioning of dependent services should be returned here. */
  metadata: Option[GenericMetadata]
)
