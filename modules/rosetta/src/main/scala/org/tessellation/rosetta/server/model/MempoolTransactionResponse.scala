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
import org.tessellation.rosetta.server.dag.schema.GenericMetadata

case class MempoolTransactionResponse(
  transaction: Transaction,
  metadata: Option[GenericMetadata]
)
