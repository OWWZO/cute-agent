type FrameNode<T> = {
  value: T;
  previous: FrameNode<T> | null;
};

/** 持久化帧链：追加只创建新节点，不复制已累计帧。 */
export class FrameBuffer<T> implements Iterable<T> {
  private constructor(
    private readonly tail: FrameNode<T> | null,
    readonly length: number
  ) {}

  static empty<T>(): FrameBuffer<T> {
    return new FrameBuffer<T>(null, 0);
  }

  static from<T>(values: Iterable<T>): FrameBuffer<T> {
    return FrameBuffer.empty<T>().append(values);
  }

  static append<T>(buffer: FrameBuffer<T>, values: Iterable<T>): FrameBuffer<T> {
    return buffer.append(values);
  }

  append(values: Iterable<T>): FrameBuffer<T> {
    let tail = this.tail;
    let length = this.length;
    for (const value of values) {
      tail = {
        value,
        previous: tail,
      };
      length += 1;
    }
    return tail === this.tail ? this : new FrameBuffer(tail, length);
  }

  filter(predicate: (value: T, index: number) => boolean): FrameBuffer<T> {
    const retained: T[] = [];
    let index = 0;
    for (const value of this) {
      if (predicate(value, index)) {
        retained.push(value);
      }
      index += 1;
    }
    return retained.length === this.length ? this : FrameBuffer.from(retained);
  }

  map<U>(mapper: (value: T, index: number) => U): U[] {
    const result: U[] = [];
    let index = 0;
    for (const value of this) {
      result.push(mapper(value, index));
      index += 1;
    }
    return result;
  }

  toArray(): T[] {
    return [...this];
  }

  [Symbol.iterator](): Iterator<T> {
    const reversed: T[] = [];
    let node = this.tail;
    while (node) {
      reversed.push(node.value);
      node = node.previous;
    }
    let index = reversed.length - 1;
    return {
      next: () => {
        if (index < 0) {
          return {
            done: true,
            value: undefined,
          };
        }
        const value = reversed[index];
        index -= 1;
        return {
          done: false,
          value,
        };
      },
    };
  }
}
