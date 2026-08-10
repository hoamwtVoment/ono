@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package moe.ono.hooks.protocol.entries

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber


@Serializable
data class TextMsgExtPbResvAttr(
    @ProtoNumber(1) val wording: ByteArray?,
)
