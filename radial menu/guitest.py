import time
import random
def gamble():
    print("Welcome to the gambling game!")
    time.sleep(1)
    print("You have 100 coins to start with.")
    coins = 100
    while coins > 0:
        print(f"You currently have {coins} coins.")
        bet = int(input("Enter your bet (or 0 to quit): "))
        if bet == 0:
            print("Thanks for playing! Goodbye!")
            break
        elif bet > coins:
            print("You don't have enough coins to make that bet. Try again.")
            continue
        else:
            outcome = random.choice(["win", "lose"])
            if outcome == "win":
                coins += bet
                print(f"You won! You now have {coins} coins.")
            else:
                coins -= bet
                print(f"You lost! You now have {coins} coins.")
    if coins <= 0:
        print("You've run out of coins! Game over.")
if __name__ == "__main__":    gamble()      