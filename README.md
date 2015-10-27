plugin-messaging-scripting
=====================

Enables ReDBox / Mint to create arbitrary message queues or topics and consume the incoming messages by executing scripts.

Add the following sample config block as an entry in the "messaging.threads" array, replacing the values where necessary:

```
{
    "id": "messagingScripts",
    "description": "Allows customisable message queues based on 'destinations' configuration below.",
    "priority": "7",
    "config": {
        "name": "messagingScripts",
        "destinations": [
            {
                "name": "testQueue",
                "type": "topic",
                "scriptPath": "home/scripts/messaging/test.groovy",
                "scriptEngine":"groovy",
                "scriptConfig": {
                    "arbitraryConfigEntry": "is this"
                }
            }
        ]
    }
} 
```