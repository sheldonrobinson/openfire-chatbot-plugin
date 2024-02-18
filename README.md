<img src=https://igniterealtime.github.io/openfire-llama-plugin/llama.png>

# Chatbot
Chatbot for Openfire.
This plugin is a wrapper to hosted AI Inference server for LLM chat models. It uses the HTTP API to create a chatbot in Openfire which will engage in XMPP chat and groupchat conversations.


## Overview
<img src="https://igniterealtime.github.io/openfire-llama-plugin/llama-chat.png" />

https://github.com/igniterealtime/openfire-llama-plugin/assets/110731/ca670d11-86b2-4018-9893-ad5946f7707a

## Installation

copy chatbot.jar to the plugins folder

## Configuration
<img src="https://igniterealtime.github.io/openfire-llama-plugin/llama-settings.png" />

### Enable Chatbot
Enables or disables the plugin. Reload plugin or restart Openfire if this or any of the settings other settings are changed.

### Host
The URL to the remote server to be used. The plugin will assume that remote server has the correct LLaMA model and configuration. It will send requests to this URL.

### Alias
Set an alias for the model. The alias will be returned in chat responses.

### System Prompt
Prompting large language models like Llama 2 is an art and a science. Set your system prompt here. Default is "You are a helpful assistant"

### Predictions
Set the maximum number of tokens to predict when generating text. Note: May exceed the set limit slightly if the last token is a partial multibyte character. When 0, no tokens will be generated but the prompt is evaluated into the cache. Default is 256

### Temperature
Adjust the randomness of the generated text (default: 0.5).

### Top K Sampling
Limit the next token selection to the K most probable tokens (default: 40).

### Top P Sampling
Limit the next token selection to a subset of tokens with a cumulative probability above a threshold P (default: 0.95).

## How to use
<img src="https://igniterealtime.github.io/openfire-llama-plugin/llama-test.png" />

To confirm that llama.cpp is working, use the demo web app to test.

The plugin will create an Openfire user called llama (by default). The user can be engaged with in chat or groupchats from any XMPP client application like Spark, Converse or Conversations.

### Chat
Add Assistant as a contact and start a chat conversation
````
(22:20) User: what are female goats called?
(22:20) Assistant:   Female goats are called does.
````
### Group Chat
You can invite assistant to join a groupchat and it will auto-accept. Type any groupchat message that starts with llama's name (llama default) and it will respond. All other messages will be ignored. 

![image](https://github.com/igniterealtime/openfire-llama-plugin/assets/110731/f5f59014-d7ec-45d3-846a-97d20c8b628e)

If a message is typed in any chat room locally and it starts with the llama name (llama by default), then it will auto join the room and respond the instruction or query.
Note that this only works with group-chats hosted in your Openfire server. Federation is not supported.
