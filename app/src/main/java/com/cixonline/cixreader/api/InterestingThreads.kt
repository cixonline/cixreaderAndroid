package com.cixonline.cixreader.api

import org.simpleframework.xml.Element
import org.simpleframework.xml.ElementList
import org.simpleframework.xml.Root
import org.simpleframework.xml.Namespace
import org.simpleframework.xml.Attribute

@Root(name = "InterestingThreadSet", strict = false)
@Namespace(reference = "http://cixonline.com")
class InterestingThreadSet {
    @field:Attribute(name = "Count", required = false)
    var count: String? = null

    @field:Attribute(name = "Start", required = false)
    var start: String? = null

    @field:ElementList(name = "Messages", entry = "InterestingThread", inline = false, required = false)
    var messages: List<InterestingThreadApi>? = null
}

@Root(name = "InterestingThread", strict = false)
@Namespace(reference = "http://cixonline.com")
class InterestingThreadApi {
    @field:Element(name = "Forum", required = false)
    var forum: String? = null

    @field:Element(name = "Topic", required = false)
    var topic: String? = null

    @field:Element(name = "RootID", required = false)
    var rootId: Int = 0

    // Adding variant just in case
    @field:Element(name = "RootId", required = false)
    var rootIdVariant: Int = 0

    val effectiveRootId: Int
        get() = if (rootId != 0) rootId else rootIdVariant

    @field:Element(name = "Author", required = false)
    var author: String? = null

    @field:Element(name = "DateTime", required = false)
    var dateTime: String? = null

    @field:Element(name = "Body", required = false)
    var body: String? = null

    @field:Element(name = "Subject", required = false)
    var subject: String? = null
}
