package com.cixonline.cixreader.api

import org.simpleframework.xml.Attribute
import org.simpleframework.xml.Element
import org.simpleframework.xml.ElementList
import org.simpleframework.xml.Namespace
import org.simpleframework.xml.Root

@Root(name = "Account", strict = false)
@Namespace(reference = "http://cixonline.com")
class Account {
    @field:Element(name = "Type", required = false)
    var type: String? = ""
}

@Root(name = "ForumResultSet", strict = false)
@Namespace(reference = "http://cixonline.com")
class ForumResultSet {
    @field:Attribute(name = "Count", required = false)
    var count: String? = ""

    @field:Attribute(name = "Start", required = false)
    var start: String? = ""

    @field:ElementList(name = "Forums", entry = "ForumRow", inline = false, required = false)
    var forums: List<ForumResultSetRow> = mutableListOf()
}

@Root(name = "ForumRow", strict = false)
@Namespace(reference = "http://cixonline.com")
class ForumResultSetRow {
    @field:Element(name = "Flags", required = false)
    var flags: String? = ""

    @field:Element(name = "Name", required = false)
    var name: String? = ""

    @field:Element(name = "Priority", required = false)
    var priority: String? = ""

    @field:Element(name = "Unread", required = false)
    var unread: String? = ""
}

@Root(name = "TopicResultSet", strict = false)
@Namespace(reference = "http://cixonline.com")
class TopicResultSet {
    @field:Element(name = "Count", required = false)
    var count: String? = ""

    @field:Element(name = "Start", required = false)
    var start: String? = ""

    @field:ElementList(name = "Topics", entry = "Topic", required = false)
    var topics: List<TopicResult> = mutableListOf()
}

@Root(name = "Topic", strict = false)
@Namespace(reference = "http://cixonline.com")
class TopicResult {
    @field:Element(name = "Name", required = false)
    var name: String? = ""

    @field:Element(name = "Desc", required = false)
    var desc: String? = ""

    @field:Element(name = "Files", required = false)
    var files: String? = ""

    @field:Element(name = "Flag", required = false)
    var flag: String? = ""
}

@Root(name = "UserTopicResultSet", strict = false)
@Namespace(reference = "http://cixonline.com")
class UserTopicResultSet {
    @field:Element(name = "Count", required = false)
    var count: String? = ""

    @field:Element(name = "Start", required = false)
    var start: String? = ""

    @field:ElementList(name = "UserTopics", entry = "UserTopic", inline = false, required = false)
    var userTopics: List<UserTopicApi> = mutableListOf()
}

@Root(name = "UserTopic", strict = false)
@Namespace(reference = "http://cixonline.com")
class UserTopicApi {
    @field:Element(name = "Flag", required = false)
    var flag: String? = ""

    @field:Element(name = "Msgs", required = false)
    var msgs: String? = ""

    @field:Element(name = "Name", required = false)
    var name: String? = ""

    @field:Element(name = "Status", required = false)
    var status: String? = ""

    @field:Element(name = "UnRead", required = false)
    var unread: String? = ""
}

@Root(name = "UserForumTopicResultSet2", strict = false)
@Namespace(reference = "http://cixonline.com")
class UserForumTopicResultSet2 {
    @field:Element(name = "Count", required = false)
    var count: String? = ""

    @field:Element(name = "Start", required = false)
    var start: String? = ""

    @field:ElementList(name = "UserTopics", entry = "UserForumTopic2", inline = false, required = false)
    var userTopics: List<UserForumTopic2> = mutableListOf()
}

@Root(name = "UserForumTopic2", strict = false)
@Namespace(reference = "http://cixonline.com")
class UserForumTopic2 {
    @field:Element(name = "Flags", required = false)
    var flags: Int = 0

    @field:Element(name = "Forum", required = false)
    var forum: String? = ""

    @field:Element(name = "Msgs", required = false)
    var msgs: String? = ""

    @field:Element(name = "Priority", required = false)
    var priority: String? = ""

    @field:Element(name = "Topic", required = false)
    var topic: String? = ""

    @field:Element(name = "UnRead", required = false)
    var unread: Int = 0

    @field:Element(name = "Recent", required = false)
    var recent: String? = ""

    @field:Element(name = "Name", required = false)
    var name: String? = ""

    @field:Element(name = "Latest", required = false)
    var latest: String? = ""
}

@Root(name = "MessageResultSet", strict = false)
@Namespace(reference = "http://cixonline.com")
class MessageResultSet {
    @field:ElementList(name = "Messages", entry = "Message", inline = false, required = false)
    var messages: List<MessageApi> = mutableListOf()
}

@Root(name = "Message", strict = false)
@Namespace(reference = "http://cixonline.com")
class MessageApi {
    @field:Element(name = "ID", required = false)
    var id: Int = 0

    @field:Element(name = "Author", required = false)
    var author: String? = ""

    @field:Element(name = "Body", required = false)
    var body: String? = ""

    @field:Element(name = "DateTime", required = false)
    var dateTime: String? = ""

    @field:Element(name = "ReplyTo", required = false)
    var replyTo: Int = 0

    @field:Element(name = "RootID", required = false)
    var rootId: Int = 0

    @field:Element(name = "Depth", required = false)
    var depth: String? = "0"
}

@Root(name = "PostMessage", strict = false)
@Namespace(reference = "http://cixonline.com")
data class PostMessageRequest(
    @field:Element(name = "Body") @param:Element(name = "Body") var body: String = "",
    @field:Element(name = "Forum") @param:Element(name = "Forum") var forum: String = "",
    @field:Element(name = "Topic") @param:Element(name = "Topic") var topic: String = "",
    @field:Element(name = "MsgID") @param:Element(name = "MsgID") var msgId: String = "0",
    @field:Element(name = "MarkRead") @param:Element(name = "MarkRead") var markRead: String = "1",
    @field:Element(name = "WrapColumn") @param:Element(name = "WrapColumn") var wrapColumn: String = "0"
)

@Root(name = "Forum", strict = false)
@Namespace(reference = "http://cixonline.com")
class ForumApi {
    @field:Element(name = "Name", required = false)
    var name: String? = ""

    @field:Element(name = "Title", required = false)
    var title: String? = ""

    @field:Element(name = "Description", required = false)
    var description: String? = ""

    @field:Element(name = "Category", required = false)
    var category: String? = ""

    @field:Element(name = "SubCategory", required = false)
    var subCategory: String? = ""

    @field:Element(name = "Type", required = false)
    var type: String? = ""
}

@Root(name = "Whos", strict = false)
@Namespace(reference = "http://cixonline.com")
class WhosApi {
    @field:ElementList(name = "Users", entry = "Who", required = false)
    var users: List<WhoApi> = mutableListOf()
}

@Root(name = "Who", strict = false)
@Namespace(reference = "http://cixonline.com")
class WhoApi {
    @field:Element(name = "Name", required = false)
    var name: String? = ""

    @field:Element(name = "Forum", required = false)
    var forum: String? = ""

    @field:Element(name = "Topic", required = false)
    var topic: String? = ""
}

@Root(name = "CategoryResultSet", strict = false)
@Namespace(reference = "http://cixonline.com")
class CategoryResultSet {
    @field:ElementList(name = "Categories", entry = "Category", inline = false, required = false)
    var categories: List<CategoryResult> = mutableListOf()
}

@Root(name = "Category", strict = false)
@Namespace(reference = "http://cixonline.com")
class CategoryResult {
    @field:Element(name = "Name", required = false)
    var name: String? = ""

    @field:Element(name = "Sub", required = false)
    var sub: String? = ""
}

@Root(name = "DirListings", strict = false)
@Namespace(reference = "http://cixonline.com")
class DirListings {
    @field:ElementList(name = "Forums", entry = "DirListing", inline = false, required = false)
    var forums: List<DirListing> = mutableListOf()
}

@Root(name = "DirListing", strict = false)
@Namespace(reference = "http://cixonline.com")
data class DirListing(
    @field:Element(name = "Forum", required = false)
    var forum: String? = "",

    @field:Element(name = "Title", required = false)
    var title: String? = "",

    @field:Element(name = "Recent", required = false)
    var recent: Int = 0,

    @field:Element(name = "Cat", required = false)
    var cat: String? = "",

    @field:Element(name = "Sub", required = false)
    var sub: String? = "",

    @field:Element(name = "Type", required = false)
    var type: String? = ""
) {
    // Required by SimpleXML for deserialization
    constructor() : this("", "", 0, "", "", "")
}
