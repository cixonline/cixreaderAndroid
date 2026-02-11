package com.cixonline.cixreader.api

import org.simpleframework.xml.Attribute
import org.simpleframework.xml.Element
import org.simpleframework.xml.ElementList
import org.simpleframework.xml.Namespace
import org.simpleframework.xml.Order
import org.simpleframework.xml.Root
import org.simpleframework.xml.Text

@Root(name = "Account", strict = false)
@Namespace(reference = "http://cixonline.com")
class Account {
    @field:Element(name = "Type", required = false)
    var type: String? = null
}

@Root(name = "UserProfile", strict = false)
@Namespace(reference = "http://cixonline.com")
class UserProfile {
    @field:Element(name = "UserName", required = false)
    var userName: String? = null

    @field:Element(name = "FullName", required = false)
    var fullName: String? = null

    @field:Element(name = "EMail", required = false)
    var email: String? = null

    @field:Element(name = "Location", required = false)
    var location: String? = null

    @field:Element(name = "Experience", required = false)
    var experience: String? = null

    @field:Element(name = "About", required = false)
    var about: String? = null

    @field:Element(name = "FirstPost", required = false)
    var firstPost: String? = null

    @field:Element(name = "LastPost", required = false)
    var lastPost: String? = null

    @field:Element(name = "FirstOn", required = false)
    var firstOn: String? = null

    @field:Element(name = "LastOn", required = false)
    var lastOn: String? = null
}

@Root(name = "Mugshot", strict = false)
@Namespace(reference = "http://cixonline.com")
class Mugshot {
    @field:Element(name = "Image", required = false)
    var image: String? = null
}

@Root(name = "Resume", strict = false)
@Namespace(reference = "http://cixonline.com")
class Resume {
    @field:Element(name = "Body", required = false)
    var body: String? = null

    @field:Text(required = false)
    var text: String? = null
}

@Root(name = "ForumResultSet", strict = false)
@Namespace(reference = "http://cixonline.com")
class ForumResultSet {
    @field:Attribute(name = "Count", required = false)
    var count: String? = null

    @field:Attribute(name = "Start", required = false)
    var start: String? = null

    @field:ElementList(name = "Forums", entry = "ForumRow", inline = false, required = false)
    var forums: List<ForumResultSetRow> = mutableListOf()
}

@Root(name = "ForumRow", strict = false)
class ForumResultSetRow {
    @field:Element(name = "Flags", required = false)
    var flags: String? = null

    @field:Element(name = "Name", required = false)
    var name: String? = null

    @field:Element(name = "Priority", required = false)
    var priority: String? = null

    @field:Element(name = "Unread", required = false)
    var unread: String? = null
}

@Root(name = "TopicResultSet", strict = false)
@Namespace(reference = "http://cixonline.com")
class TopicResultSet {
    @field:Element(name = "Count", required = false)
    var count: String? = null

    @field:Element(name = "Start", required = false)
    var start: String? = null

    @field:ElementList(name = "Topics", entry = "Topic", required = false)
    var topics: List<TopicResult> = mutableListOf()
}

@Root(name = "Topic", strict = false)
class TopicResult {
    @field:Element(name = "Name", required = false)
    var name: String? = null

    @field:Element(name = "Desc", required = false)
    var desc: String? = null

    @field:Element(name = "Files", required = false)
    var files: String? = null

    @field:Element(name = "Flag", required = false)
    var flag: String? = null
}

@Root(name = "UserTopicResultSet", strict = false)
@Namespace(reference = "http://cixonline.com")
class UserTopicResultSet {
    @field:Element(name = "Count", required = false)
    var count: String? = null

    @field:Element(name = "Start", required = false)
    var start: String? = null

    @field:ElementList(name = "UserTopics", entry = "UserTopic", inline = false, required = false)
    var userTopics: List<UserTopicApi> = mutableListOf()
}

@Root(name = "UserTopic", strict = false)
class UserTopicApi {
    @field:Element(name = "Flag", required = false)
    var flag: String? = null

    @field:Element(name = "Msgs", required = false)
    var msgs: String? = null

    @field:Element(name = "Name", required = false)
    var name: String? = null

    @field:Element(name = "Status", required = false)
    var status: String? = null

    @field:Element(name = "UnRead", required = false)
    var unread: String? = null
}

@Root(name = "UserForumTopicResultSet2", strict = false)
@Namespace(reference = "http://cixonline.com")
class UserForumTopicResultSet2 {
    @field:Element(name = "Count", required = false)
    var count: String? = null

    @field:Element(name = "Start", required = false)
    var start: String? = null

    @field:ElementList(name = "UserTopics", entry = "UserForumTopic2", inline = false, required = false)
    var userTopics: List<UserForumTopic2> = mutableListOf()
}

@Root(name = "UserForumTopic2", strict = false)
class UserForumTopic2 {
    @field:Element(name = "Flags", required = false)
    var flags: Int = 0

    @field:Element(name = "Forum", required = false)
    var forum: String? = null

    @field:Element(name = "Msgs", required = false)
    var msgs: String? = null

    @field:Element(name = "Priority", required = false)
    var priority: String? = null

    @field:Element(name = "Topic", required = false)
    var topic: String? = null

    @field:Element(name = "UnRead", required = false)
    var unread: Int = 0

    @field:Element(name = "Recent", required = false)
    var recent: String? = null

    @field:Element(name = "Name", required = false)
    var name: String? = null

    @field:Element(name = "Latest", required = false)
    var latest: String? = null
}

@Root(name = "MessageResultSet", strict = false)
@Namespace(reference = "http://cixonline.com")
class MessageResultSet {
    @field:Attribute(name = "Count", required = false)
    var count: String? = null

    @field:Attribute(name = "Start", required = false)
    var start: String? = null

    @field:ElementList(name = "Messages", entry = "Message", inline = false, required = false)
    var messages: List<MessageApi> = mutableListOf()
}

@Root(name = "Message", strict = false)
class MessageApi {
    @field:Element(name = "ID", required = false)
    var id: Int = 0

    @field:Element(name = "Author", required = false)
    var author: String? = null

    @field:Element(name = "Subject", required = false)
    var subject: String? = null

    @field:Element(name = "Body", required = false)
    var body: String? = null

    @field:Element(name = "DateTime", required = false)
    var dateTime: String? = null

    @field:Element(name = "ReplyTo", required = false)
    var replyTo: Int = 0

    @field:Element(name = "RootID", required = false)
    var rootId: Int = 0

    @field:Element(name = "Depth", required = false)
    var depth: String? = "0"

    @field:Element(name = "Forum", required = false)
    var forum: String? = null

    @field:Element(name = "Topic", required = false)
    var topic: String? = null
}

@Root(name = "Attachment", strict = false)
@Order(elements = ["Filename", "EncodedData"])
data class PostAttachment(
    @field:Element(name = "Filename") @param:Element(name = "Filename") var filename: String = "",
    @field:Element(name = "EncodedData") @param:Element(name = "EncodedData") var data: String = ""
)

@Root(name = "PostMessage", strict = false)
@Namespace(reference = "http://cixonline.com")
@Order(elements = ["Body", "Forum", "Topic", "MsgID", "MarkRead"])
data class PostMessageRequest @JvmOverloads constructor(
    @field:Element(name = "Body") @param:Element(name = "Body") var body: String = "",
    @field:Element(name = "Forum") @param:Element(name = "Forum") var forum: String = "",
    @field:Element(name = "Topic") @param:Element(name = "Topic") var topic: String = "",
    @field:Element(name = "MsgID") @param:Element(name = "MsgID") var msgId: Int = 0,
    @field:Element(name = "MarkRead") @param:Element(name = "MarkRead") var markRead: Int = 1
)

@Root(name = "PostMessage2", strict = false)
@Namespace(reference = "http://cixonline.com")
@Order(elements = ["Body", "Forum", "Topic", "MsgID", "MarkRead", "Flags", "Attachments"])
data class PostMessage2Request @JvmOverloads constructor(
    @field:Element(name = "Body") @param:Element(name = "Body") var body: String = "",
    @field:Element(name = "Forum") @param:Element(name = "Forum") var forum: String = "",
    @field:Element(name = "Topic") @param:Element(name = "Topic") var topic: String = "",
    @field:Element(name = "MsgID") @param:Element(name = "MsgID") var msgId: Int = 0,
    @field:Element(name = "MarkRead") @param:Element(name = "MarkRead") var markRead: Int = 1,
    @field:Element(name = "Flags") @param:Element(name = "Flags") var flags: Int = 0,
    @field:ElementList(name = "Attachments", entry = "Attachment", required = false, inline = false) 
    @param:ElementList(name = "Attachments", entry = "Attachment", required = false, inline = false)
    var attachments: List<PostAttachment>? = null
)

@Root(name = "PostMessage2Response", strict = false)
@Namespace(reference = "http://cixonline.com")
class PostMessage2Response {
    @field:Element(name = "MessageNumber", required = false)
    var messageNumber: Int = 0

    @field:Element(name = "Response", required = false)
    var response: String? = null

    @field:Element(name = "Body", required = false)
    var body: String? = null
}

@Root(name = "Attachment", strict = false)
class AttachmentResponse {
    @field:Element(name = "Filename", required = false)
    var filename: String? = null

    @field:Element(name = "URL", required = false)
    var url: String? = null
}

@Root(name = "Forum", strict = false)
@Namespace(reference = "http://cixonline.com")
class ForumApi {
    @field:Element(name = "Name", required = false)
    var name: String? = null

    @field:Element(name = "Title", required = false)
    var title: String? = null

    @field:Element(name = "Description", required = false)
    var description: String? = null

    @field:Element(name = "Category", required = false)
    var category: String? = null

    @field:Element(name = "SubCategory", required = false)
    var subCategory: String? = null

    @field:Element(name = "Type", required = false)
    var type: String? = null
}

@Root(name = "Whos", strict = false)
@Namespace(reference = "http://cixonline.com")
class WhosApi {
    @field:ElementList(name = "Users", entry = "Who", required = false)
    var users: List<WhoApi> = mutableListOf()
}

@Root(name = "Who", strict = false)
class WhoApi {
    @field:Element(name = "Name", required = false)
    var name: String? = null

    @field:Element(name = "Forum", required = false)
    var forum: String? = null

    @field:Element(name = "Topic", required = false)
    var topic: String? = null
}

@Root(name = "CategoryResultSet", strict = false)
@Namespace(reference = "http://cixonline.com")
class CategoryResultSet {
    @field:ElementList(name = "Categories", entry = "Category", inline = false, required = false)
    var categories: List<CategoryResult> = mutableListOf()
}

@Root(name = "Category", strict = false)
class CategoryResult {
    @field:Element(name = "Name", required = false)
    var name: String? = null

    @field:Element(name = "Sub", required = false)
    var sub: String? = null
}

@Root(name = "DirListings", strict = false)
@Namespace(reference = "http://cixonline.com")
class DirListings {
    @field:ElementList(name = "Forums", entry = "DirListing", inline = false, required = false)
    var forums: List<DirListing> = mutableListOf()
}

@Root(name = "DirListing", strict = false)
data class DirListing(
    @field:Element(name = "Forum", required = false)
    var forum: String? = null,

    @field:Element(name = "Title", required = false)
    var title: String? = null,

    @field:Element(name = "Recent", required = false)
    var recent: Int = 0,

    @field:Element(name = "Cat", required = false)
    var cat: String? = null,

    @field:Element(name = "Sub", required = false)
    var sub: String? = null,

    @field:Element(name = "Type", required = false)
    var type: String? = null
) {
    // Required by SimpleXML for deserialization
    constructor() : this(null, null, 0, null, null, null)
}
