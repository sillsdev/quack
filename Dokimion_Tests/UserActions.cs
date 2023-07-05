﻿using NUnit.Framework.Interfaces;
using OpenQA.Selenium;
using OpenQA.Selenium.Chrome;

namespace Dokimion
{
    public class UserActions
    {
        public string? DokimionUrl = TestContext.Parameters.Get("Url");
        
        public string? ActorName = TestContext.Parameters.Get("ActorName");
        public string? Username = TestContext.Parameters["Username"];
        public string? Password = TestContext.Parameters.Get("Password");
        public string? InvalidUser = TestContext.Parameters.Get("InvalidUser");
        public string? InvalidPassword = TestContext.Parameters.Get("InvalidPassword");
        public string? DisplayUserName = TestContext.Parameters.Get("DisplayUserName");

        public readonly string Headless = "--headless=new";
        public readonly string WindowSize =  "--window-size=1920,1080";
        public ChromeOptions GetChromeOptions()
        {

            var options = new ChromeOptions();
            options.PageLoadStrategy = PageLoadStrategy.Normal;

            options.AddArgument(Headless);
            options.AddArgument(WindowSize);
            options.AddArgument("--disable-gpu"); //helps with headless 
            options.AddArgument("--disable-site-isolation-trials");
            return options;
        }


        //Logs error or exception stack trace from each test case
        public void TearDownAfterTestcase()
        {
            var resultOutcome = TestContext.CurrentContext.Result.Outcome;

            if (Equals(resultOutcome, ResultState.Failure) ||
                     Equals(resultOutcome == ResultState.Error))
            {
                LogConsoleMessage(TestContext.CurrentContext.Test.FullName + " : " + resultOutcome);
                if (TestContext.CurrentContext.Result.StackTrace != null)
                {
                    LogConsoleMessage(TestContext.CurrentContext.Result.StackTrace);
                }


            }
        }

        public void LogConsoleMessage(string message)
        {
            TestContext.Progress.WriteLine(message);
        }

    }
}
