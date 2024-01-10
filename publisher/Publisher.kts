import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import com.google.gson.Gson
import javax.imageio.ImageIO
import java.awt.image.BufferedImage
import java.security.MessageDigest

val ARTICLE_IMAGES_PATH = "assets/images/article_images/"
val ARTICLE_TOPICS_FILE = "publisher/article-topics.txt"
val KEYWORDS_FILE = "publisher/keywords.txt"

val authors = setOf(
        "alex",
        "paul",
        "emily",
        "zoe",
        "lily",
        "amelia"
)
val keywords = readKeywords(KEYWORDS_FILE)

for (i in 0..4) {
    val articleTopic = readFirstLine(ARTICLE_TOPICS_FILE)
    if (articleTopic.isNullOrBlank()) {
        println("No article topics found.")
        break
    }

    //generate image
    val imageFile = "${articleTopic.toMD5()}.jpg"
    val imageFullPath = File(ARTICLE_IMAGES_PATH + imageFile)
    if (!imageFullPath.exists()) {
        val dallePrompt = generateDallePrompt(articleTopic)
        val imgUrl = generateImage(dallePrompt)
        if (imgUrl == null) break

        saveDalleImage(imgUrl, articleTopic.toMD5())
    } else {
        println("Image file exists: $imageFile")
    }

    //generate article content
    var articleContent: String
    val articleKeywords = keywords.shuffled()
            .subList(0, 7)
    do {
        articleContent = generateArticle(
                topic = articleTopic,
                keywords = articleKeywords.joinToString("\n")
        )
        Thread.sleep(3)
    } while (articleContent.isEmpty())

    saveArticle(articleTopic.split(":").first(), articleContent, articleKeywords, imageFile)

    deleteFirstLine(ARTICLE_TOPICS_FILE)
}

fun generateArticle(topic: String, keywords: String): String {
    println("Generating article: $topic")

    val formattedKeywords = keywords
            .trim()
            .split("\n")
            .joinToString(", ") {
                it.trim()
            }
    val appStoreLink = "https://apps.apple.com/us/app/ai-ask-chat-with-ai-bots/id6472484891"

    val prompt = """
        As an expert in website SEO and mobile app marketing, your task is to create a SEO-optimized blog post that promotes the ChatGPT AI Chat iOS app, a sophisticated mobile application featuring the ChatGPT AI chatbot. The post should organically boost the website's visibility on search engines, drive traffic to the app store page, and encourage downloads.

        Topic: "$topic"

        Objectives:
        SEO Optimization: Integrate these keywords naturally throughout the article and SEO optimize the article for them: $keywords.
        Engaging and Informative Content: The article should be captivating, providing in-depth insights into the app's features, benefits, practical applications, and it should offer real value to readers.
        Tone: Use a more personal and authentic tone.
        Length: 1500 words.
        Call-to-Action: Conclude each article with a compelling call-to-action, directing readers to the app store link for downloads.
        Formatting: Format the article for easy integration into a Jekyll .md blog post file, while ensuring SEO
        App Store Link Integration: Include the provided app store link in each article, ensuring it's prominently placed and easily accessible. App store link: $appStoreLink

        Remember, the article should adhere to the guidelines above, offering unique, valuable insights to encourage app exploration and downloads.
        Your response will only contain the formatted article content, nothing else. Do not return the YAML Front Matter.
        """

    return askChatGPT(prompt)
}

fun askChatGPT(prompt: String): String {
    println("Asking ChatGPT")

    val prompt = prompt.trimMargin()
            .trimIndent()
            .replace("\"", "\\\"")
            .split("\n")
            .joinToString("\\n") {
                it.trim()
            }

    val apiUrl = "https://api.openai.com/v1/chat/completions"
    val apiKey = System.getenv("OPEN_AI_KEY")

    val url = URL(apiUrl)
    val httpURLConnection = (url.openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        doOutput = true
        setRequestProperty("Content-Type", "application/json")
        setRequestProperty("Authorization", "Bearer $apiKey")

//    "model": "gpt-4-0314",
        val payload = """
        {
          "model": "gpt-4-1106-preview",
          "messages": [
            {
              "role": "user",
              "content": "$prompt"
            }
          ],
          "top_p": 0.7,
          "temperature": 1.1,
          "stream": false
        }
    """
                .trimIndent()
                .split("\n")
                .joinToString("\n") {
                    it.trim()
                }
        outputStream.write(payload.toByteArray())
    }

    return if (httpURLConnection.responseCode == HttpURLConnection.HTTP_OK) {
        val jsonStringResponse = httpURLConnection.inputStream.bufferedReader().readText()

        println("Got successful GPT response")

        val gson = Gson()
        val response = gson.fromJson(jsonStringResponse, ChatGptResponse::class.java)

        response.choices.firstOrNull()?.message?.content
                ?: throw IllegalStateException("Article not generated! Api response:\n$jsonStringResponse")
    } else {
        println("API request failed with response code: ${httpURLConnection.responseCode}\n\n${String(httpURLConnection.errorStream.readAllBytes())}")
        ""
    }
}

fun saveArticle(title: String,
                content: String,
                articleKeywords: List<String>,
                imageFile: String) {
    println("Saving article")

    val postsDirectory = "_posts"
    val currentDateTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
    val fileName = "$currentDateTime-${title.replace("[^A-Za-z0-9]".toRegex(), "-")}.md"
    val filePath = "$postsDirectory/$fileName"
    val markdownContent = """
        ---
        layout: post
        title:  $title
        author: ${authors.random()}
        categories: [${articleKeywords.joinToString(", ")}]
        image: "$ARTICLE_IMAGES_PATH$imageFile"
        featured: true
        ---
    """.trimIndent() + "\n\n" + content

    File(filePath).writeText(markdownContent)
    println("Article saved as $filePath")
}

fun generateDallePrompt(topic: String): String {
    println("Generating the DALL-E-3 prompt for the article image.")

    val prompt = """
        Assume you're an expert in website SEO, mobile app marketing, mobile app design, and the DALL-E-3 AI. I'm developing an AI chatbot app for iOS, powered by ChatGPT technology. I want to promote this app through my website's blog.

        Your task is to create DALL-E-3 prompts that I can use to generate engaging images for my blog articles to captivate my audience.

        I will give you the article title and you will return only the DALL-E-3 prompt for it: 
        $topic

        Remember, return only the DALL-E-3 prompt and nothing else.
    """.trimIndent()

    return askChatGPT(prompt)
            .replace("\"", "")
}

fun generateImage(dallePrompt: String): String {
    println("Generating the article image using the DALL-E-3 prompt.")

    val apiUrl = "https://api.openai.com/v1/images/generations"
    val apiKey = System.getenv("OPEN_AI_KEY")

    val url = URL(apiUrl)
    val httpURLConnection = (url.openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        doOutput = true
        setRequestProperty("Content-Type", "application/json")
        setRequestProperty("Authorization", "Bearer $apiKey")

        val payload = """
        {
          "model": "dall-e-3",
          "prompt": "${dallePrompt.replace("\"", "")}",
          "n": 1,
          "size": "1792x1024",
          "quality": "standard",
          "style": "natural"
        }
    """
                .trimIndent()
                .split("\n")
                .joinToString("\n") {
                    it.trim()
                }
        outputStream.write(payload.toByteArray())
    }

    return if (httpURLConnection.responseCode == HttpURLConnection.HTTP_OK) {
        val jsonStringResponse = httpURLConnection.inputStream.bufferedReader().readText()

        println("Got successful GPT response")

        val gson = Gson()
        val response = gson.fromJson(jsonStringResponse, DalleResponse::class.java)

        response.data.firstOrNull()?.url
                ?: throw IllegalStateException("Image not generated! Api response:\n$jsonStringResponse")
    } else {
        println("Dalle API request failed with response code: ${httpURLConnection.responseCode}")
        ""
    }

    """
        {
          "created": 1704883350,
          "data": [
            {
              "revised_prompt": "A lavish depiction of a white Siamese cat. The feline is sitting elegantly, its fur brushed to perfection and glistening under soft sunlight. Its eyes, a shade of mesmerizing blue, are large and expressive, displaying a sense of curiosity. The cat's slender tail wraps around its body, adding to the overall graceful look. Background adds a further layer of interest, with a cozy domestic environment dispatching a sense of warmth and homely comfort.",
              "url": "https://oaidalleapiprodscus.blob.core.windows.net/private/org-C4AwUWahNN3k6ourx2qbXcib/user-PwVdesVJzO8Zdp6QrvTjUFbb/img-tii53d3cSCOwtseORkarN3wQ.png?st=2024-01-10T09%3A42%3A30Z&se=2024-01-10T11%3A42%3A30Z&sp=r&sv=2021-08-06&sr=b&rscd=inline&rsct=image/png&skoid=6aaadede-4fb3-4698-a8f6-684d7786b067&sktid=a48cca56-e6da-484e-a814-9c849652bcb3&skt=2024-01-10T02%3A22%3A32Z&ske=2024-01-11T02%3A22%3A32Z&sks=b&skv=2021-08-06&sig=jSF/RHv63irwDkU4hG15dImYN7%2B0p2%2BlfEH0mzozF8o%3D"
            }
          ]
        }

    """.trimIndent()
}

fun saveDalleImage(imageUrl: String, fileName: String) {
    val imageFullPath = ARTICLE_IMAGES_PATH + "$fileName.jpg"

    println("Saving article image under $imageFullPath.")

    try {
        // Download the PNG image from the URL
        val inputImage: BufferedImage = ImageIO.read(URL(imageUrl))

        // Convert the image to JPG
        val outputImage = BufferedImage(inputImage.width, inputImage.height, BufferedImage.TYPE_INT_RGB)
        outputImage.graphics.drawImage(inputImage, 0, 0, null)

        // Save the JPG image to disk
        val outputFile = File(imageFullPath)
        ImageIO.write(outputImage, "jpg", outputFile)

        println("Image downloaded and converted to JPG format at: ${outputFile.absolutePath}")
    } catch (e: Exception) {
        println("An error occurred: ${e.message}")
    }
}

fun String.toMD5(): String {
    val md = MessageDigest.getInstance("MD5")
    val digest = md.digest(this.toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
}

fun readFirstLine(filePath: String): String {
    return File(filePath).useLines { it.firstOrNull() ?: "" }
}

fun deleteFirstLine(filePath: String) {
    val file = File(filePath)
    val lines = file.readLines().drop(1)
    file.writeText(lines.joinToString("\n"))
}

fun readKeywords(filePath: String): List<String> {
    return File(filePath).readLines()
}

data class ChatGptResponse(
        val choices: List<Choice>,
)

data class Choice(
        val message: Message,
)

data class Message(
        val content: String
)

data class DalleResponse(
        val data: List<DalleData>
)

data class DalleData(
        val url: String
)