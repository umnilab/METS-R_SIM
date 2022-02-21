#!/usr/bin/python3

# WS server example

import asyncio
import websockets
import sys

def predict_demand(time_of_day):
    # ML stuff goes here
    
    
    return "result"


async def server(websocket, path):
    msg = await websocket.recv()
    print(f"prediction required for time of day : {msg}")

    greeting = f"hello {msg}!"

    await websocket.send(greeting)


def main():
    if(len(sys.argv) < 2):
        print("python3 demand_prediction.py <port_number>")
        return

    port = int(sys.argv[1])
    start_server = websockets.serve(server, "localhost", port)
    asyncio.get_event_loop().run_until_complete(start_server)
    asyncio.get_event_loop().run_forever()


if __name__ == "__main__":
    main()
