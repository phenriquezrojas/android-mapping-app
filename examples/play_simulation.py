#!/usr/bin/env python3
import json
import socket
import time
import sys

def play_simulation(target_ip, json_path):
    print(f"Reading simulation data from: {json_path}")
    with open(json_path, 'r') as f:
        data = json.load(f)
        
    frames = data.get("frames", [])
    duration = data.get("duration_seconds", 30)
    bpm = data.get("bpm", 120)
    
    print(f"Loaded {len(frames)} frames ({duration}s at {bpm} BPM).")
    print(f"Target: {target_ip}:60222 via UDP")
    print("Press Ctrl+C to stop.")
    
    # Initialize socket
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    
    try:
        while True:
            start_time = time.time()
            for frame in frames:
                frame_start = time.time()
                
                # Convert hex to raw bytes
                packet_bytes = bytes.fromhex(frame["packet_hex"])
                
                # Send packet
                sock.sendto(packet_bytes, (target_ip, 60222))
                
                # Precise sleep to match 50ms frame rate (20 FPS)
                elapsed = time.time() - frame_start
                sleep_time = max(0.0, 0.050 - elapsed)
                time.sleep(sleep_time)
                
            print("\nLoop finished. Restarting simulation...")
            
    except KeyboardInterrupt:
        print("\nPlayback stopped by user.")
    finally:
        sock.close()

if __name__ == '__main__':
    # Default target is local or standard Android emulator address
    ip = "127.0.0.1"
    if len(sys.argv) > 1:
        ip = sys.argv[1]
        
    path = "/Users/a3209977/Proyects/MappingAndroid/examples/numark_simulation_30s.json"
    play_simulation(ip, path)
