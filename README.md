# Concurrent Cinema Reservation System

ระบบจองที่นั่งโรงภาพยนตร์แบบ Client-Server ด้วยภาษา Java สำหรับสาธิต Producer-Consumer Message Queue, การทำงานพร้อมกันของ Worker threads, Shared Resource, Race Condition และการควบคุม Critical Section ด้วย `Semaphore(1)`

โปรเจกต์นี้ช้ TCP Socket สำหรับการสื่อสารระหว่าง Client กับ Server และใช้ `ArrayBlockingQueue<RequestMessage>` เป็น Message Queue ภายใน Server

## คุณสมบัติของระบบ

- ที่นั่งจำนวน 20 ที่นั่ง หมายเลข 1-20
- Request Queue ความจุ 50 รายการ
- รองรับ Client หลายตัวพร้อมกันผ่าน TCP port `8080`
- กำหนดจำนวน Worker ได้ตอนเปิด Server
- มีโหมด `sync` และ `nosync` สำหรับเปรียบเทียบผลของ Synchronization
- ใช้ random delay 50-500 ms ระหว่าง CHECK และ UPDATE ของคำสั่ง `RESERVE`
- มี `RaceTest` สำหรับส่งคำขอจองที่นั่งเดียวกันจาก Client จำลองหลายตัวพร้อมกัน
- รองรับการ Build และ Run ด้วย Java โดยตรงหรือ Docker

## โครงสร้างการทำงาน

```text
Client
  |
  | TCP Socket : 8080
  v
ClientHandler (Producer)
  |
  | RequestMessage
  v
ArrayBlockingQueue (capacity 50)
  |
  v
WorkerTask (Consumer)
  |
  | Semaphore(1) in sync mode
  v
Shared Seat[]
```

`ClientHandler` อ่านคำสั่งจาก Socket สร้าง `RequestMessage` และเพิ่มลงใน Request Queue ส่วน `WorkerTask` ดึงคำขอออกจากคิวและประมวลผลกับข้อมูล `Seat[]` ที่ใช้ร่วมกัน

Message Queue ทำให้การเพิ่มและนำงานออกจากคิวปลอดภัย แต่ไม่ได้ทำให้ขั้นตอน CHECK และ UPDATE ของที่นั่งเป็น Atomic ดังนั้นโหมด `sync` จึงใช้ `Semaphore(1)` ป้องกัน Race Condition แยกต่างหาก

## ไฟล์ในโปรเจกต์

```text
.
├── Server.java      # Server, Request Queue, Worker และ Shared Seat Table
├── Client.java      # Interactive Client
├── RaceTest.java    # โปรแกรมจำลอง Client หลายตัวพร้อมกัน
├── Dockerfile       # สภาพแวดล้อม Java 17 สำหรับ Docker
├── README.md        # วิธี Build, Run และทดสอบระบบ
```

## สิ่งที่ต้องมี

### การรันด้วย Java โดยตรง

- JDK 17 หรือใหม่กว่า

ตรวจสอบ Java:

```bash
java -version
javac -version
```

### การรันด้วย Docker

- Docker Desktop หรือ Docker Engine
- Linux containers

ตรวจสอบ Docker:

```bash
docker version
```

## Build ด้วย Java

เปิด Terminal ที่โฟลเดอร์โปรเจกต์แล้วรัน:

```bash
javac -d out Server.java Client.java RaceTest.java
```

ไฟล์ `.class` จะถูกสร้างในโฟลเดอร์ `out/`

## การเปิด Server

รูปแบบคำสั่ง:

```bash
java -cp out Server [sync|nosync] [workerCount]
```

เปิด Server แบบมี Synchronization และใช้ 3 Workers:

```bash
java -cp out Server sync 3
```

เปิด Server แบบไม่มี Synchronization และใช้ 3 Workers:

```bash
java -cp out Server nosync 3
```

เปิด Server แบบมี Worker เพียง 1 ตัว:

```bash
java -cp out Server sync 1
```

โหมดและจำนวน Worker ถูกอ่านตอนเริ่ม Server หากต้องการเปลี่ยนค่าให้หยุด Server ด้วย `Ctrl+C` แล้วเปิดใหม่

## การเปิด Interactive Client

เปิด Terminal ใหม่โดยปล่อย Terminal ของ Server ทำงานค้างไว้:

```bash
java -cp out Client Client-1 localhost
```

เปิด Client เพิ่มได้โดยเปลี่ยน Client ID:

```bash
java -cp out Client Client-2 localhost
java -cp out Client Client-3 localhost
java -cp out Client Client-4 localhost
java -cp out Client Client-5 localhost
```

หาก Client อยู่คนละเครื่อง ให้แทน `localhost` ด้วย IPv4 address ของเครื่องที่รัน Server:

```bash
java -cp out Client Client-1 192.168.1.174
```

ทั้งสองเครื่องต้องเชื่อมต่อถึงกัน และ Firewall ของเครื่อง Server ต้องอนุญาต TCP port `8080`

## คำสั่งที่ Client รองรับ

```text
LIST
STATUS <seat_id>
RESERVE <seat_id>
CANCEL <seat_id>
QUIT
```

ตัวอย่าง:

```text
LIST
STATUS 10
RESERVE 10
CANCEL 10
QUIT
```

หมายเลขที่นั่งต้องอยู่ระหว่าง 1-20 และ Client สามารถยกเลิกได้เฉพาะที่นั่งที่จองด้วย Client ID ของตนเอง

## การใช้ RaceTest

รูปแบบคำสั่ง:

```bash
java -cp out RaceTest [serverHost] [seatId] [clientCount]
```

ให้ Client จำลอง 5 ตัวส่งคำขอจองที่นั่งหมายเลข 10 พร้อมกัน:

```bash
java -cp out RaceTest localhost 10 5
```

`RaceTest` ใช้ `CountDownLatch` เตรียม Client ทุกตัวให้พร้อมก่อนปล่อยคำขอพร้อมกัน แต่ละ Client ใช้ Socket ของตนเองและส่งคำสั่ง `RESERVE` สำหรับที่นั่งเดียวกัน

## การทดลองทั้ง 3 กรณี

ต้องหยุดและเปิด Server ใหม่ก่อนทุกกรณี เพื่อคืนสถานะที่นั่งทั้งหมดเป็น AVAILABLE

### Experiment 1: Sequential Baseline

Terminal 1:

```bash
java -cp out Server sync 1
```

Terminal 2:

```bash
java -cp out RaceTest localhost 10 5
```

ผลที่คาดหวัง: มี `SUCCESS` 1 ราย และ `FAILED` 4 ราย เนื่องจาก Worker เพียงตัวเดียวประมวลผลคำขอทีละรายการ

### Experiment 2: Concurrent without Synchronization

Terminal 1:

```bash
java -cp out Server nosync 3
```

Terminal 2:

```bash
java -cp out RaceTest localhost 10 5
```

ผลที่สังเกต: อาจมี Client มากกว่าหนึ่งรายได้รับ `SUCCESS` สำหรับที่นั่งเดียวกัน เพราะ Worker หลายตัวสามารถตรวจพบ AVAILABLE ก่อนเกิด UPDATE หากรอบแรกไม่แสดง Race Condition ให้หยุด Server แล้วทดลองใหม่

### Experiment 3: Concurrent with Synchronization

Terminal 1:

```bash
java -cp out Server sync 3
```

Terminal 2:

```bash
java -cp out RaceTest localhost 10 5
```

ผลที่คาดหวัง: มี `SUCCESS` เพียง 1 ราย และ `FAILED` 4 ราย โดย Server log จะแสดงการ `ENTER` และ `LEAVE` Critical Section

## Build และ Run ด้วย Docker

### ต้อง Build Image เมื่อใด

ตรวจสอบก่อนว่าเครื่องมี Image อยู่แล้วหรือไม่:

```bash
docker image ls
```

ต้องรัน `docker build` ในกรณีต่อไปนี้:

- ยังไม่มี Image ชื่อ `theater-system`
- มีการแก้ไข `Server.java`, `Client.java` หรือ `RaceTest.java`
- มีการแก้ไข `Dockerfile`
- Image เดิมถูกลบ หรือกำลังใช้งานบนเครื่องใหม่
- ต้องการให้ Container ใช้โค้ดเวอร์ชันล่าสุด

สร้าง Image จากโฟลเดอร์ที่มี `Dockerfile` และไฟล์ Java:

```bash
docker build -t theater-system .
```

หาก Build ครั้งล่าสุดล้มเหลว แต่ `docker image ls` ยังแสดง `theater-system:latest` แสดงว่า Image เก่ายังคงอยู่ การรัน Container ในตอนนั้นจะใช้โค้ดเวอร์ชันเก่า

### กรณีที่ไม่ต้อง Build ใหม่

ไม่ต้อง Build Image ใหม่เมื่อโค้ดและ `Dockerfile` ไม่มีการเปลี่ยนแปลง รวมถึงกรณีต่อไปนี้:

- เปลี่ยนโหมดระหว่าง `sync` และ `nosync`
- เปลี่ยนจำนวน Worker
- เริ่มการทดลองรอบใหม่เพื่อรีเซ็ตข้อมูลที่นั่ง
- เปิด Interactive Client เพิ่ม
- รัน `RaceTest` เพิ่มหรือเปลี่ยนหมายเลขที่นั่งและจำนวน Client

โหมด จำนวน Worker หมายเลขที่นั่ง และจำนวน Client เป็น argument ตอนรันโปรแกรม จึงไม่ถูกเก็บเป็นค่าตายตัวใน Image

### เปิด Server Container

เปิด Server แบบ `sync` และใช้ 3 Workers:

```bash
docker run --rm --name theater-server -p 8080:8080 \
  theater-system java Server sync 3
```

การเปลี่ยนเป็นโหมด `nosync` ไม่ต้อง Build ใหม่ ให้หยุด Container เดิมด้วย `Ctrl+C` แล้วเปิดใหม่:

```bash
docker run --rm --name theater-server -p 8080:8080 \
  theater-system java Server nosync 3
```

การเปลี่ยนจำนวน Worker ใช้วิธีเดียวกัน เช่น Sequential Baseline ที่มี Worker 1 ตัว:

```bash
docker run --rm --name theater-server -p 8080:8080 \
  theater-system java Server sync 1
```

Server เก็บข้อมูลที่นั่งไว้ในหน่วยความจำ การหยุดและเปิด Container ใหม่จึงทำให้ที่นั่งทั้งหมดกลับเป็น AVAILABLE โดยไม่ต้อง Build Image ใหม่

### เปิด Client และ RaceTest

เปิด Client ภายใน Container จาก Terminal ใหม่:

```bash
docker exec -it theater-server java Client Client-1 localhost
```

เปิด RaceTest ภายใน Container:

```bash
docker exec -it theater-server java RaceTest localhost 10 5
```

อีกทางเลือกหนึ่งคือรัน Client หรือ RaceTest จากเครื่อง Host หลังจาก Compile ด้วย `javac -d out ...` แล้ว โดยเชื่อมต่อมาที่ `localhost:8080`

สรุปลำดับการใช้ Docker คือ Build Image เมื่อโค้ดเปลี่ยน จากนั้นสามารถ Run, Stop และ Run Container ใหม่ได้หลายครั้งโดยใช้ Image เดิม

## การอ่าน Server Log

Server log แสดงข้อมูลสำคัญ เช่น:

- `seq` และเวลา
- การ `ENQUEUE` และ `DEQUEUE`
- Worker ที่ประมวลผลคำขอ
- Client ID, command และ seat ID
- การ `CHECK` และ `UPDATE` ที่นั่ง
- การ `ENTER` และ `LEAVE` Critical Section ในโหมด `sync`

ตัวอย่าง:

```text
[Queue] ENQUEUE RESERVE seat=10 from RaceClient-1
[Worker-1] DEQUEUE RESERVE seat=10 from RaceClient-1
[Worker-1] ENTER critical section (RESERVE seat 10)
[Worker-1] CHECK seat 10: AVAILABLE
[Worker-1] UPDATE seat 10 reserved by RaceClient-1
[Worker-1] LEAVE critical section (RESERVE seat 10)
```

## การหยุดระบบ

- Interactive Client: ใช้คำสั่ง `QUIT`
- Server หรือ RaceTest: กด `Ctrl+C` เมื่อจำเป็น
- Docker Server ที่รันอยู่ด้านหน้า: กด `Ctrl+C`

ข้อมูลการจองอยู่ในหน่วยความจำและจะถูกรีเซ็ตทุกครั้งที่เริ่ม Server process ใหม่
